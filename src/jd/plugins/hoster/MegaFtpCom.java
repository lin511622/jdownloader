//    jDownloader - Downloadmanager
//    Copyright (C) 2008  JD-Team support@jdownloader.org
//
//    This program is free software: you can redistribute it and/or modify
//    it under the terms of the GNU General Public License as published by
//    the Free Software Foundation, either version 3 of the License, or
//    (at your option) any later version.
//
//    This program is distributed in the hope that it will be useful,
//    but WITHOUT ANY WARRANTY; without even the implied warranty of
//    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
//    GNU General Public License for more details.
//
//    You should have received a copy of the GNU General Public License
//    along with this program.  If not, see <http://www.gnu.org/licenses/>.
package jd.plugins.hoster;

import jd.PluginWrapper;
import jd.parser.html.Form;
import jd.plugins.DownloadLink;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.Plugin;
import jd.plugins.PluginException;
import jd.plugins.PluginForHost;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.utils.locale.JDL;

@HostPlugin(revision = "$Revision$", interfaceVersion = 2, names = { "megaftp.com" }, urls = { "http://[\\w\\.]*?megaftp\\.com/[0-9]+" }, flags = { 0 })
public class MegaFtpCom extends PluginForHost {

    public MegaFtpCom(PluginWrapper wrapper) {
        super(wrapper);
    }

    // @Override
    public String getAGBLink() {
        return "http://www.megaftp.com/contact";
    }

    // @Override
    public AvailableStatus requestFileInformation(DownloadLink downloadLink) throws Exception, PluginException, InterruptedException {
        this.setBrowserExclusive();

        br.setFollowRedirects(true);
        br.getPage(downloadLink.getDownloadURL());
        if (br.containsHTML("404 Not Found")) throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);

        String filename = br.getRegex("<b><font color=\"#000000\" size=\"4\">File Name: </font><font color=\"#FC8622\" size=\"4\">(.*?)</font></b>").getMatch(0);
        if (filename == null) throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        downloadLink.setName(filename.trim());
        br.setFollowRedirects(false);
        return AvailableStatus.TRUE;
    }

    // @Override
    public void handleFree(DownloadLink downloadLink) throws Exception {
        requestFileInformation(downloadLink);
        // Datei hat Passwortschutz?
        if (br.containsHTML("This file is password-protected")) {
            String passCode;
            DownloadLink link = downloadLink;
            Form form = br.getFormbyProperty("name", "pswcheck");
            if (link.getStringProperty("pass", null) == null) {
                /* Usereingabe */
                passCode = Plugin.getUserInput(null, link);
            } else {
                /* gespeicherten PassCode holen */
                passCode = link.getStringProperty("pass", null);
            }

            /* Passwort übergeben */
            form.put("psw", passCode);
            br.submitForm(form);

            form = br.getFormbyProperty("name", "pswcheck");
            if (form != null && br.containsHTML("Invalid Password")) {
                link.setProperty("pass", null);
                throw new PluginException(LinkStatus.ERROR_FATAL, JDL.L("plugins.errors.wrongpassword", "Password wrong"));
            } else {
                link.setProperty("pass", passCode);
            }
        }
        // often they only change this form
        Form downloadForm = br.getForm(0);
        String current = br.getRegex("name=\"current\" value=\"(.*?)\"").getMatch(0);
        String wait = br.getRegex("wait for the countdown.*?<script type=\"text/javascript\">.*?var.*?= (\\d+);").getMatch(0);
        if (wait != null) sleep(Long.parseLong(wait.trim()) * 1000, downloadLink);
        if (current == null) throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFEKT);
        downloadForm.put("current", current);
        downloadForm.put("limit_reached", "0");
        downloadForm.put("download_now", "Click+Here+to+Download");
        br.setFollowRedirects(true);
        dl = jd.plugins.BrowserAdapter.openDownload(br, downloadLink, downloadForm, true, -20);
        if (!(dl.getConnection().isContentDisposition()) && !dl.getConnection().getContentType().contains("octet")) {
            dl.getConnection().disconnect();
            throw new PluginException(LinkStatus.ERROR_FATAL);
        }
        dl.startDownload();
    }

    // @Override
    public int getMaxSimultanFreeDownloadNum() {
        return 20;
    }

    // @Override
    public void reset() {
    }

    public void resetDownloadlink(DownloadLink link) {
    }
}
