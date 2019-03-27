package jd.plugins.download.raf;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicBoolean;

import jd.http.Browser;
import jd.http.URLConnectionAdapter;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.download.Downloadable;

import org.appwork.exceptions.WTFException;
import org.appwork.storage.config.JsonConfig;
import org.appwork.utils.Exceptions;
import org.appwork.utils.ReusableByteArrayOutputStream;
import org.appwork.utils.logging2.LogInterface;
import org.appwork.utils.logging2.LogSource;
import org.appwork.utils.net.httpconnection.HTTPConnection.RequestMethod;
import org.appwork.utils.net.throttledconnection.MeteredThrottledInputStream;
import org.appwork.utils.os.CrossSystem;
import org.appwork.utils.speedmeter.AverageSpeedMeter;
import org.jdownloader.settings.GeneralSettings;
import org.jdownloader.translate._JDT;

public class RAFChunk extends Thread {
    private static final String             UNEXPECTED_RANGE_HEADER_FORMAT         = "Unexpected Range Header Format";
    private static final String             CHUNKLOAD_NOT_SUPPORTED                = "Chunkload not supported";
    private static final String             COULD_NOT_CLONE_CONNECTION             = "Could Not Clone Connection";
    private static final String             NETWORK_PROBLEMS                       = "Network Problems: ";
    private static final String             NO_IO_PREMISSION_TO_WRITE_ON_HARDDRIVE = "No IOPremission to write on harddrive";
    private static final String             TEMP_NOT_AVAILABLE                     = "Temp. not available";
    /**
     * Wird durch die Speedbegrenzung ein chunk uter diesen Wert geregelt, so wird er weggelassen. Sehr niedrig geregelte chunks haben einen
     * kleinen Buffer und eine sehr hohe Intervalzeit. Das fuehrt zu verstaerkt intervalartigem laden und ist ungewuenscht
     */
    public static final long                MIN_CHUNKSIZE                          = 100 * 1024;
    private long                            chunkBytesLoaded                       = 0;
    private URLConnectionAdapter            connection;
    private long                            endByte;
    private final int                       id;
    private MeteredThrottledInputStream     inputStream;
    private long                            startByte;
    private long                            bytes2Do                               = -1;
    private AtomicBoolean                   connectionclosed                       = new AtomicBoolean(false);
    private long                            requestedEndByte;
    private OldRAFDownload                  dl;
    private Downloadable                    downloadable;
    private LogInterface                    logger;
    protected ReusableByteArrayOutputStream buffer                                 = null;
    private AtomicBoolean                   running                                = new AtomicBoolean(false);
    private URLConnectionAdapter            originalConnection;

    public URLConnectionAdapter getOriginalConnection() {
        return originalConnection;
    }

    public URLConnectionAdapter getCurrentConnection() {
        return connection;
    }

    public boolean isRunning() {
        return running.get();
    }

    /**
     * Die Connection wird entsprechend der start und endbytes neu aufgebaut.
     *
     * @param startByte
     * @param endByte
     * @param connection
     */
    public RAFChunk(long startByte, long endByte, URLConnectionAdapter connection, OldRAFDownload dl, Downloadable link, int id) {
        super("DownloadChunkRAF:" + link.getName());
        running.set(true);
        this.startByte = startByte;
        this.id = id;
        this.endByte = endByte;
        this.requestedEndByte = endByte;
        this.originalConnection = connection;
        this.dl = dl;
        this.downloadable = link;
        this.logger = dl.getLogger();
        if (CrossSystem.isWindows()) {
            /* workaround for windows multimedia stuff. it reduces priority for non active(in background) stuff */
            try {
                this.setPriority(NORM_PRIORITY + 2);
            } catch (final Throwable e) {
            }
        }
    }

    @Deprecated
    public int getMaximalSpeed() {
        return 0;
    }

    @Deprecated
    public void setMaximalSpeed(final int i) {
    }

    private void addChunkBytesLoaded(long limit) {
        chunkBytesLoaded += limit;
    }

    /**
     * Gibt Fortschritt in % an (10000 entspricht 100%))
     *
     * @return
     */
    public int getPercent() {
        return (int) (10000 * chunkBytesLoaded / Math.max(1, Math.max(chunkBytesLoaded, (endByte - startByte))));
    }

    /**
     * Kopiert die Verbindung. Es wird bis auf die Range und timeouts exakt die selbe Verbindung nochmals aufgebaut.
     *
     * @param connection
     * @return
     * @throws InterruptedException
     */
    private URLConnectionAdapter copyConnection(final URLConnectionAdapter connection) {
        final long start = startByte;
        final String end = (endByte > 0 ? endByte + 1 : "") + "";
        final long[] connectionRange = connection.getRange();
        if (connectionRange == null && start == 0) {
            // TODO: check if it is possible to reach endByte with connection
            logger.finer("Takeover connection(no range) for Start:" + startByte + "|End:" + endByte);
            return connection;
        }
        if (connectionRange != null && connectionRange[0] == (start)) {
            logger.finer("Takeover connection(" + Arrays.toString(connectionRange) + ") for Start:" + startByte + "|End:" + endByte);
            // TODO: check if it is possible to reach endByte with connection
            return connection;
        }
        try {
            downloadable.waitForNextConnectionAllowed();
        } catch (InterruptedException e1) {
            LogSource.exception(logger, e1);
            return null;
        }
        try {
            /* only forward referer if referer already has been sent! */
            final Browser br = downloadable.getContextBrowser();
            boolean forwardReferer = br.getHeaders().contains("Referer");
            br.setReadTimeout(dl.getReadTimeout());
            br.setConnectTimeout(dl.getRequestTimeout());
            /* set requested range */
            final Map<String, String> request = connection.getRequestProperties();
            if (request != null) {
                String value;
                for (Entry<String, String> next : request.entrySet()) {
                    if (next.getValue() == null) {
                        continue;
                    }
                    value = next.getValue().toString();
                    br.getHeaders().put(next.getKey(), value);
                }
            }
            if (!forwardReferer) {
                /* only forward referer if referer already has been sent! */
                br.setCurrentURL(null);
            }
            URLConnectionAdapter con = null;
            boolean returnConnection = false;
            try {
                if (connection.getRequestMethod() == RequestMethod.POST) {
                    connection.getRequest().getHeaders().put("Range", "bytes=" + start + "-" + end);
                    con = br.openRequestConnection(connection.getRequest());
                } else {
                    br.getHeaders().put("Range", "bytes=" + start + "-" + end);
                    con = br.openGetConnection(connection.getURL() + "");
                }
                if (!con.isOK()) {
                    if (con.getResponseCode() != 416) {
                        dl.error(new PluginException(LinkStatus.ERROR_DOWNLOAD_INCOMPLETE, "Server: " + con.getResponseMessage()));
                    } else {
                        logger.warning("HTTP 416, maybe finished last chunk?");
                    }
                    return null;
                }
                if (con.getRequest().getLocation() != null) {
                    dl.error(new PluginException(LinkStatus.ERROR_DOWNLOAD_INCOMPLETE, "Server: Redirect"));
                    return null;
                }
                returnConnection = true;
                return con;
            } finally {
                if (!returnConnection) {
                    try {
                        /* always close connections that got opened */
                        if (con != null) {
                            con.disconnect();
                        }
                    } catch (Throwable e) {
                    }
                }
            }
        } catch (Exception e) {
            dl.error(new PluginException(LinkStatus.ERROR_RETRY, Exceptions.getStackTrace(e)));
            LogSource.exception(logger, e);
        }
        return null;
    }

    /** Die eigentliche Downloadfunktion */
    private void download() {
        int flushLevel = 0;
        int flushTimeout = JsonConfig.create(GeneralSettings.class).getFlushBufferTimeout();
        try {
            int maxbuffersize = JsonConfig.create(GeneralSettings.class).getMaxBufferSize() * 1024;
            buffer = new ReusableByteArrayOutputStream(Math.max(maxbuffersize, 10240));
            /*
             * now we calculate the max fill level when to force buffer flushing
             */
            flushLevel = Math.max((maxbuffersize / 100 * JsonConfig.create(GeneralSettings.class).getFlushBufferLevel()), 1);
        } catch (Throwable e) {
            dl.error(new PluginException(LinkStatus.ERROR_FATAL, "OOM", e).localizedMessage(_JDT.T.download_error_message_outofmemory()));
            return;
        }
        /* +1 because of startByte also gets loaded (startbyte till endbyte) */
        if (endByte > 0) {
            bytes2Do = (endByte - startByte) + 1;
        }
        boolean remoteIO = false;
        try {
            if (connection == null) {
                throw new WTFException("connection null");
            }
            if (dl == null) {
                throw new WTFException("connection null");
            }
            connection.setReadTimeout(dl.getReadTimeout());
            connection.setConnectTimeout(dl.getRequestTimeout());
            inputStream = new MeteredThrottledInputStream(connection.getInputStream(), new AverageSpeedMeter(10)) {
                public void close() throws IOException {
                };
            };
            dl.getManagedConnetionHandler().addThrottledConnection(inputStream);
            int toWrite = 0;
            int read = 0;
            boolean reachedEOF = false;
            long lastFlush = 0;
            long bytesRead = 0;
            long bytesWritten = 0;
            while (!isExternalyAborted() && !connectionclosed.get()) {
                try {
                    buffer.reset();
                    if (reachedEOF == true) {
                        /* we already reached EOF, so nothing more to read */
                        toWrite = -1;
                    } else {
                        /* lets try to read some data */
                        toWrite = 0;
                    }
                    lastFlush = System.currentTimeMillis();
                    while (!reachedEOF && buffer.free() > 0 && buffer.size() <= flushLevel && !isExternalyAborted() && !connectionclosed.get()) {
                        final int bufLeft = buffer.free();
                        if (endByte > 0) {
                            /* read only as much as needed */
                            remoteIO = true;
                            int readMaxNext = (int) Math.min(bytes2Do, bufLeft);
                            final long bytes2DoBf = bytes2Do;
                            if (bytes2Do > 0 && bytes2Do < 512 * 1024) {
                                if (bytes2Do < 32767) {
                                    readMaxNext = (int) Math.min(bytes2Do, 32);
                                } else {
                                    readMaxNext = 512;
                                }
                                readMaxNext = Math.min(readMaxNext, bufLeft);
                            }
                            try {
                                read = inputStream.read(buffer.getInternalBuffer(), buffer.size(), readMaxNext);
                            } catch (IOException e) {
                                logger.warning("Chunk(" + getID() + "):" + e.getMessage() + "|todo:" + bytes2Do + "|readMax" + readMaxNext);
                                throw e;
                            }
                            if (read > 0) {
                                bytes2Do -= read;
                                if (bytes2Do == 0) {
                                    /* we reached our artificial EOF */
                                    logger.warning("reached artificial EOF");
                                    reachedEOF = true;
                                } else if (bytes2Do < 0) {
                                    logger.warning("Chunk(" + getID() + "):WTF, where is EOF?!|todo:" + bytes2Do + "|todoBf:" + bytes2DoBf + "|readMax:" + readMaxNext);
                                    reachedEOF = true;
                                }
                            }
                        } else {
                            /* read as much as possible */
                            remoteIO = true;
                            read = inputStream.read(buffer.getInternalBuffer(), buffer.size(), bufLeft);
                        }
                        if (read > 0) {
                            /* we read some data */
                            bytesRead += read;
                            toWrite += read;
                            dl.totalLinkBytesLoadedLive.getAndAdd(read);
                            buffer.setUsed(toWrite);
                        } else if (read == -1) {
                            /* we reached EOF */
                            logger.warning("reached EOF");
                            reachedEOF = true;
                        } else {
                            /*
                             * wait a moment, give system chance to fill up its buffers
                             */
                            synchronized (this) {
                                this.wait(500);
                            }
                        }
                        if (System.currentTimeMillis() - lastFlush > flushTimeout) {
                            /* we reached our flush timeout */
                            break;
                        }
                    }
                } catch (NullPointerException e) {
                    LogSource.exception(logger, e);
                    throw e;
                } catch (IOException e4) {
                    LogSource.exception(logger, e4);
                    if (toWrite > 0) {
                        logger.warning("flush:exClosed:" + isExternalyAborted() + "|conClosed:" + connectionclosed + "|read:" + bytesRead + "|toWrite:" + toWrite + "|written:" + bytesWritten);
                        try {
                            final long flush = toWrite;
                            toWrite = -1;
                            dl.addToTotalLinkBytesLoaded(flush, false);
                            addChunkBytesLoaded(flush);
                            dl.writeBytes(this);
                            bytesWritten += flush;
                            logger.warning("flushed:exClosed:" + isExternalyAborted() + "|conClosed:" + connectionclosed + "|read:" + bytesRead + "|toWrite:" + toWrite + "|written:" + bytesWritten);
                        } catch (final Throwable throwable) {
                            LogSource.exception(logger, throwable);
                        }
                    }
                    if (!isExternalyAborted() && !connectionclosed.get()) {
                        throw e4;
                    }
                    toWrite = -1;
                    break;
                }
                if (toWrite == -1 || isExternalyAborted() || connectionclosed.get()) {
                    if (toWrite > 0) {
                        logger.warning("flush:exClosed:" + isExternalyAborted() + "|conClosed:" + connectionclosed + "|read:" + bytesRead + "|toWrite:" + toWrite + "|written:" + bytesWritten);
                        try {
                            final long flush = toWrite;
                            toWrite = -1;
                            dl.addToTotalLinkBytesLoaded(flush, false);
                            addChunkBytesLoaded(flush);
                            dl.writeBytes(this);
                            bytesWritten += flush;
                            logger.warning("flushed:exClosed:" + isExternalyAborted() + "|conClosed:" + connectionclosed + "|read:" + bytesRead + "|toWrite:" + toWrite + "|written:" + bytesWritten);
                        } catch (final Throwable throwable) {
                            LogSource.exception(logger, throwable);
                        }
                    }
                    logger.warning("break:exClosed:" + isExternalyAborted() + "|conClosed:" + connectionclosed + "|read:" + bytesRead + "|toWrite:" + toWrite + "|written:" + bytesWritten);
                    break;
                }
                if (toWrite > 0) {
                    remoteIO = false;
                    final long flush = toWrite;
                    toWrite = -1;
                    dl.addToTotalLinkBytesLoaded(flush, false);
                    addChunkBytesLoaded(flush);
                    dl.writeBytes(this);
                    bytesWritten += flush;
                }
                /* enough bytes loaded */
                if (bytes2Do == 0 && endByte > 0) {
                    break;
                }
                if (getCurrentBytesPosition() > endByte && endByte > 0) {
                    break;
                }
            }
            logger.info("ExternalAbort: " + isExternalyAborted());
            long endPosition = endByte;
            if (endPosition < 0) {
                endPosition = downloadable.getVerifiedFileSize();
            }
            if (endPosition >= 0 && getCurrentBytesPosition() < endPosition) {
                logger.warning("Download not finished. Loaded until now: " + getCurrentBytesPosition() + "/" + endPosition + " read:" + bytesRead + " written:" + bytesWritten);
                dl.error(new PluginException(LinkStatus.ERROR_DOWNLOAD_INCOMPLETE, "Download Incomplete").localizedMessage(_JDT.T.download_error_message_incomplete()));
            }
        } catch (FileNotFoundException e) {
            LogSource.exception(logger, e);
            if (remoteIO) {
                dl.error(new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND, null));
            } else {
                dl.error(new PluginException(LinkStatus.ERROR_DOWNLOAD_FAILED, NO_IO_PREMISSION_TO_WRITE_ON_HARDDRIVE, LinkStatus.VALUE_LOCAL_IO_ERROR).localizedMessage(_JDT.T.download_error_message_iopermissions()));
            }
        } catch (SecurityException e) {
            LogSource.exception(logger, e);
            logger.severe("not enough rights to write the file. " + e.getLocalizedMessage());
            dl.error(new PluginException(LinkStatus.ERROR_DOWNLOAD_FAILED, NO_IO_PREMISSION_TO_WRITE_ON_HARDDRIVE, LinkStatus.VALUE_LOCAL_IO_ERROR).localizedMessage(_JDT.T.download_error_message_iopermissions()));
        } catch (UnknownHostException e) {
            LogSource.exception(logger, e);
            dl.error(new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, TEMP_NOT_AVAILABLE, 10 * 60000l).localizedMessage(_JDT.T.download_error_message_unavailable()));
        } catch (IOException e) {
            LogSource.exception(logger, e);
            if (e.getMessage() != null && e.getMessage().contains("reset")) {
                logger.info("Connection reset: network problems!");
                dl.error(new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, NETWORK_PROBLEMS + e.getMessage(), 1000l * 60 * 5).localizedMessage(_JDT.T.download_error_message_networkreset()));
            } else if (e.getMessage() != null && e.getMessage().indexOf("timed out") >= 0) {
                LogSource.exception(logger, e);
                logger.severe("Read timeout: network problems! (too many connections?, firewall/antivirus?)");
                dl.error(new PluginException(LinkStatus.ERROR_DOWNLOAD_INCOMPLETE, NETWORK_PROBLEMS + e.getMessage(), LinkStatus.VALUE_NETWORK_IO_ERROR).localizedMessage(_JDT.T.download_error_message_networkreset()));
            } else if (e instanceof SocketException) {
                logger.info("Socket Exception: network problems!");
                dl.error(new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, NETWORK_PROBLEMS + e.getMessage(), 1000l * 60 * 5).localizedMessage(_JDT.T.download_error_message_networkreset()));
            } else {
                LogSource.exception(logger, e);
                if (e.getMessage() != null && e.getMessage().contains("503")) {
                    dl.error(new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, TEMP_NOT_AVAILABLE, 10 * 60000l).localizedMessage(_JDT.T.download_error_message_unavailable()));
                } else if (remoteIO) {
                    dl.error(new PluginException(LinkStatus.ERROR_DOWNLOAD_INCOMPLETE, NETWORK_PROBLEMS + e.getMessage(), LinkStatus.VALUE_NETWORK_IO_ERROR).localizedMessage(_JDT.T.download_error_message_networkreset()));
                } else {
                    logger.severe("error occurred while writing to file. " + e.getMessage());
                    dl.error(new PluginException(LinkStatus.ERROR_DOWNLOAD_FAILED, NO_IO_PREMISSION_TO_WRITE_ON_HARDDRIVE, LinkStatus.VALUE_LOCAL_IO_ERROR).localizedMessage(_JDT.T.download_error_message_iopermissions()));
                }
            }
        } catch (Throwable e) {
            LogSource.exception(logger, e);
            dl.error(new PluginException(LinkStatus.ERROR_RETRY, Exceptions.getStackTrace(e)));
        } finally {
            try {
                inputStream.setHandler(null);
                dl.getManagedConnetionHandler().removeThrottledConnection(inputStream);
            } catch (final Throwable ignore) {
            } finally {
                inputStream = null;
            }
            try {
                /* we can close cloned connections here */
                if (getOriginalConnection() != getCurrentConnection()) {
                    getCurrentConnection().disconnect();
                }
            } catch (Throwable e) {
            }
        }
    }

    /**
     * Gibt die Geladenen ChunkBytes zurueck
     *
     * @return
     */
    public long getBytesLoaded() {
        return getCurrentBytesPosition() - startByte;
    }

    public long getChunkSize() {
        return endByte - startByte + 1;
    }

    /**
     * Gibt die Aktuelle Endposition in der gesamtfile zurueck. Diese Methode gibt die Endposition unahaengig davon an Ob der aktuelle
     * BUffer schon geschrieben wurde oder nicht.
     *
     * @return
     */
    public long getCurrentBytesPosition() {
        return startByte + chunkBytesLoaded;
    }

    public long getEndByte() {
        return endByte;
    }

    public int getID() {
        return id;
    }

    public long getStartByte() {
        return startByte;
    }

    /**
     * Gibt die Schreibposition des Chunks in der gesamtfile zurueck
     *
     * @throws Exception
     */
    public long getWritePosition() throws Exception {
        long c = getCurrentBytesPosition();
        long l = buffer.size();
        return c - l;
    }

    /**
     * Gibt zurueck ob der chunk von einem externen eregniss unterbrochen wurde
     *
     * @return
     */
    private boolean isExternalyAborted() {
        return (dl != null && dl.externalDownloadStop()) || downloadable.isInterrupted();
    }

    /**
     * Thread runner
     */
    public void run() {
        try {
            run0();
        } finally {
            running.set(false);
            dl.onChunkFinished(this);
        }
    }

    public void run0() {
        try {
            logger.finer("Start Chunk " + getID() + " : " + startByte + " - " + endByte);
            long endCheck = endByte;
            if (endCheck < 0) {
                endCheck = downloadable.getVerifiedFileSize();
            }
            if (startByte >= endCheck && endCheck >= 0) {
                return;
            }
            if (dl.getChunkNum() > 1) {
                /* we requested multiple chunks */
                connection = copyConnection(getOriginalConnection());
                if (connection == null) {
                    if (this.isExternalyAborted()) {
                        logger.severe("ExternalyAborted: no copyConnection for " + getID());
                        return;
                    }
                    /* copy failed!, lets check if this is the last chunk */
                    if (startByte >= endCheck && endCheck >= 0) {
                        logger.finer("Is no error. Last chunk is just already finished");
                        return;
                    }
                    dl.error(new PluginException(LinkStatus.ERROR_DOWNLOAD_INCOMPLETE, COULD_NOT_CLONE_CONNECTION).localizedMessage(_JDT.T.download_error_message_connectioncopyerror()));
                    logger.severe("ERROR Chunk (connection copy failed) " + getID());
                    return;
                }
            } else if (startByte > 0) {
                connection = copyConnection(getOriginalConnection());
                if (connection == null && this.isExternalyAborted()) {
                    logger.severe("ExternalyAborted: no copyConnection for " + getID());
                    return;
                }
                /* workaround for last chunk already finished */
                if (startByte >= endCheck && endCheck >= 0) {
                    logger.finer("Is no error. Last chunk is just already finished");
                    return;
                }
                if (connection == null) {
                    dl.error(new PluginException(LinkStatus.ERROR_DOWNLOAD_INCOMPLETE, COULD_NOT_CLONE_CONNECTION).localizedMessage(_JDT.T.download_error_message_connectioncopyerror()));
                    logger.severe("ERROR Chunk (connection copy failed) " + getID());
                    return;
                }
                if (startByte > 0 && (connection.getHeaderField("Content-Range") == null || connection.getHeaderField("Content-Range").length() == 0)) {
                    dl.error(new PluginException(LinkStatus.ERROR_DOWNLOAD_INCOMPLETE, CHUNKLOAD_NOT_SUPPORTED).localizedMessage(_JDT.T.download_error_message_rangeheaders()));
                    logger.severe("ERROR Chunk (no range header response)" + getID() + "\r\n" + connection.toString());
                    // logger.finest(connection.toString());
                    return;
                }
            } else {
                connection = copyConnection(originalConnection);
            }
            final long[] ContentRange = connection.getRange();
            final long contentLength = connection.getLongContentLength();
            if (startByte >= 0) {
                /* startByte >0, we should have a Content-Range in response! */
                if (ContentRange != null && ContentRange.length == 3) {
                    if (endByte >= 0 && endByte != ContentRange[1]) {
                        logger.info("EndByte missmatch(1)! " + endByte + "!=" + ContentRange[1]);
                    }
                    endByte = ContentRange[1];
                } else if (dl.getChunkNum() > 1) {
                    /* WTF? no Content-Range response available! */
                    if (contentLength == startByte) {
                        /*
                         * Content-Length equals startByte -> Chunk is Complete!
                         */
                        return;
                    }
                    if (ContentRange == null && startByte == 0 && contentLength == -1) {
                        /*
                         * no contentRange response and no contentLength, we assume the complete content will be served
                         */
                    } else if (ContentRange == null && startByte == 0 && contentLength >= endByte) {
                        /*
                         * no contentRange response, but the Content-Length is long enough and startbyte begins at 0, so it might be a
                         * rangeless first Request
                         */
                    } else {
                        logger.severe("ERROR Chunk (range header parse error)" + getID() + "\r\n" + connection.toString());
                        dl.error(new PluginException(LinkStatus.ERROR_DOWNLOAD_INCOMPLETE, UNEXPECTED_RANGE_HEADER_FORMAT).localizedMessage(_JDT.T.download_error_message_rangeheaderparseerror() + connection.getHeaderField("Content-Range")));
                        return;
                    }
                } else {
                    /* only one chunk requested, set correct endByte */
                    if (contentLength > 0) {
                        final long end = contentLength - 1;
                        if (endByte >= 0 && endByte != end) {
                            logger.info("EndByte missmatch(2)! " + endByte + "!=" + end);
                        }
                        endByte = end;
                    } else {
                        logger.severe("no contentLength available, endByte remains " + endByte);
                    }
                }
            } else if (ContentRange != null) {
                /*
                 * we did not request a range but got a content-range response,WTF?!
                 */
                logger.severe("No Range Request -> Content-Range Response?!");
                if (endByte > 0 && endByte != ContentRange[1]) {
                    logger.info("EndByte missmatch! " + endByte + "!=" + ContentRange[1]);
                }
                endByte = ContentRange[1];
            }
            if (endByte <= 0) {
                /* endByte not yet set!, use Content-Length */
                if (contentLength > 0) {
                    final long end = contentLength - 1;
                    if (endByte >= 0 && endByte != end) {
                        logger.info("EndByte missmatch(3)! " + endByte + "!=" + end);
                    }
                    endByte = end;
                } else {
                    logger.severe("no contentLength available, endByte remains " + endByte);
                }
            }
            long cRequestedEndByte = requestedEndByte + 1;
            if (cRequestedEndByte > 0 && endByte > cRequestedEndByte) {
                if (this.getID() == 0) {
                    logger.info("First Connection -> Content-Range(" + endByte + ") is larger than requested (" + cRequestedEndByte + ")! Truncate it");
                } else {
                    logger.info(this.getID() + ". Connection -> Content-Range(" + endByte + ") is larger than requested (" + cRequestedEndByte + ")! Truncate it");
                }
                endByte = cRequestedEndByte;
            }
            download();
            logger.finer("Chunk finished " + getID() + " " + getBytesLoaded() + " bytes");
        } finally {
            try {
                /* we can close cloned connections here */
                if (getOriginalConnection() != getCurrentConnection()) {
                    getCurrentConnection().disconnect();
                }
            } catch (Throwable e) {
            }
        }
    }

    /**
     * Setzt die anzahl der schon geladenen partbytes. Ist fuer resume wichtig.
     *
     * @param loaded
     */
    public void setLoaded(long loaded) {
        loaded = Math.max(0, loaded);
        dl.addToTotalLinkBytesLoaded(loaded, true);
    }

    public void startChunk() {
        start();
    }

    public void closeConnections() {
        connectionclosed.set(true);
        if (Thread.currentThread().isAlive() == false) {
            running.set(false);
        }
        super.interrupt();
        try {
            inputStream.close();
        } catch (Throwable e) {
        }
        try {
            getCurrentConnection().disconnect();
        } catch (Throwable e) {
        } finally {
            originalConnection = null;
        }
        try {
            getOriginalConnection().disconnect();
        } catch (Throwable e) {
        } finally {
            connection = null;
        }
    }

    public MeteredThrottledInputStream getInputStream() {
        return inputStream;
    }
}
