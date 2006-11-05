package syndie.gui;

import javax.naming.ldap.Rdn;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.TreeItem;
import syndie.data.ChannelInfo;
import syndie.data.NymReferenceNode;
import syndie.data.ReferenceNode;
import syndie.data.SyndieURI;
import syndie.db.DBClient;

/**
 *
 */
class ReferenceChooserPopup implements ReferenceChooserTree.ChoiceListener, ReferenceChooserTree.AcceptanceListener {
    private Shell _parent;
    private DBClient _client;
    private Shell _shell;
    private ReferenceChooserTree.AcceptanceListener _lsnr;
    private ReferenceChooserTree _tree;
    private ReferenceChooserSearch _search;
    private ReferenceChooserInfo _info;
    
    public ReferenceChooserPopup(Shell parent, DBClient client, ReferenceChooserTree.AcceptanceListener lsnr) {
        _parent = parent;
        _client = client;
        _lsnr = lsnr;
        initComponents();
    }
    
    public void show() { _shell.setVisible(true); }
    public void hide() { _shell.setVisible(false); }
    
    private void initComponents() {
        if (_parent == null)
            _shell = new Shell(Display.getDefault(), SWT.SHELL_TRIM);
        else
            _shell = new Shell(_parent, SWT.SHELL_TRIM);
        _shell.setText("Reference chooser");
        _shell.setLayout(new FillLayout());
        
        SashForm sash = new SashForm(_shell, SWT.HORIZONTAL);
        _tree = new ReferenceChooserTree(_client, sash, this, this);
        
        Composite right = new Composite(sash, SWT.NONE);
        right.setLayout(new GridLayout(1, true));
        _search = new ReferenceChooserSearch(right, _tree);
        GridData gd = new GridData(GridData.FILL_BOTH);
        gd.grabExcessHorizontalSpace = true;
        gd.grabExcessVerticalSpace = false;
        _search.getControl().setLayoutData(gd);
        _info = new ReferenceChooserInfo(right, _tree, this);
        gd = new GridData(GridData.FILL_BOTH);
        gd.grabExcessHorizontalSpace = true;
        gd.grabExcessVerticalSpace = true;
        _info.getControl().setLayoutData(gd);
        
        _shell.pack();
    }

    public void bookmarkSelected(TreeItem item, NymReferenceNode node) { _info.bookmarkSelected(item, node); }
    public void manageChannelSelected(TreeItem item, ChannelInfo channel) { _info.manageChannelSelected(item, channel); }
    public void postChannelSelected(TreeItem item, ChannelInfo channel) { _info.postChannelSelected(item, channel); }
    public void searchResultSelected(TreeItem item, ReferenceNode node) { _info.searchResultSelected(item, node); }
    public void otherSelected(TreeItem item) { _info.otherSelected(item); }

    public void referenceAccepted(SyndieURI uri) {
        _shell.setVisible(false);
        _lsnr.referenceAccepted(uri);
    }
    public void referenceChoiceAborted() {
        _shell.setVisible(false);
        _lsnr.referenceChoiceAborted();
    }
}
