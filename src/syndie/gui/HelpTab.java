package syndie.gui;

import java.io.File;

import net.i2p.util.FileUtil;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.MessageBox;

import syndie.data.SyndieURI;

/**
 *  Simple display of HTML(?) text at the top level of the browser.
 *  Only for "help" URIs.
 *
 *  The URI params "file" supplies the location of the file, relative to the doc/ directory.
 *  @since 1.102b-8
 */
public class HelpTab extends  PageRendererTab {
    
    private static final int MAX_LINES = 2000;

    private boolean _success;

    public HelpTab(BrowserControl browser, SyndieURI uri) {
        super(browser, uri);
    }
    
    @Override
    protected void initComponents() {
        getRoot().setLayout(new FillLayout());
        _renderer = ComponentBuilder.instance().createPageRenderer(getRoot(), true);
        _renderer.setListener(this);
        
        getBrowser().getThemeRegistry().register(this);
        getBrowser().getTranslationRegistry().register(this);
    }

    @Override
    public void show(SyndieURI uri) {
        String body = null;
        String path = getURI().getString("file");
        if (path == null)
            path = "index.html";
        if (path.endsWith(".html")) {
            File f = new File(path);
            if (!f.isAbsolute() && !path.contains("..")) {
                f = new File(getClient().getRootDir(), "help");
                f = new File(f, path);
                // TODO use WebRipRunner to fixup links on-the-fly?
                // TODO PageRenderer expects images to be message attachments?
                body = FileUtil.readTextFile(f.toString(), MAX_LINES, true);
            }
        }
        if (body == null) {
            MessageBox box = new MessageBox(getRoot().getShell(), SWT.ICON_INFORMATION | SWT.OK);
            box.setText(getBrowser().getTranslationRegistry().getText("File not found"));
            box.setMessage(getBrowser().getTranslationRegistry().getText("File not found"));
            getBrowser().getNavControl().unview(getURI());
            box.open();
            return;
        }    
        super.tabShown();
        // TODO get title from <title> ?
        show(body, "Help", "tooltip");
    }
    
    @Override
    public Image getIcon() { return ImageUtil.ICON_HM_ABOUT; }
}