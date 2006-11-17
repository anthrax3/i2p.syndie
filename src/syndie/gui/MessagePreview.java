package syndie.gui;

import java.util.List;
import java.util.Map;
import java.util.Properties;
import net.i2p.data.Hash;
import net.i2p.data.PrivateKey;
import net.i2p.data.SessionKey;
import net.i2p.data.SigningPrivateKey;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.events.MouseListener;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.layout.RowData;
import org.eclipse.swt.layout.RowLayout;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Text;
import syndie.Constants;
import syndie.data.ChannelInfo;
import syndie.data.MessageInfo;
import syndie.data.ReferenceNode;
import syndie.data.SyndieURI;
import syndie.db.DBClient;

/**
 *
 */
public class MessagePreview {
    private DBClient _client;
    private Composite _parent;
    private Composite _root;
    
    private Composite _header;
    private Image _headerAvatar;
    private Image _headerAvatarDefault;
    private ImageCanvas _headerAvatarCanvas;
    private Text _headerSubject;
    private Label _headerDate;
    private Label _headerAuthor;
    private Label _headerStatus;
    private Label _headerPrivacy;
    private Label _headerInfo;
    private Menu _headerInfoMenu;
    private Combo _headerPages;
    private Combo _headerActions;
    
    private AttachmentPreviewPopup _attachmentPopup;
    
    private PageRenderer _body;
    private SyndieURI _uri;
    private int _page;
    
    public MessagePreview(DBClient client, Composite parent) {
        _client = client;
        _parent = parent;
        initComponents();
    }
    
    public void preview(SyndieURI uri) {
        _uri = uri;
        Long page = uri.getPage();
        if (page == null)
            _page = 1;
        else
            _page = page.intValue();
        updatePreview();
    }
    
    public Control getControl() { return _root; }

    private MessageInfo getMessage() {
        if ( (_uri == null) || (_uri.getScope() == null) )
            return null;
        long chanId = _client.getChannelId(_uri.getScope());
        return _client.getMessage(chanId, _uri.getMessageId());
    }
    
    private void showPage() {
        _page = _headerPages.getSelectionIndex()+1;
        updatePreview();
    }
    
    private void updatePreview() {
        MessageInfo msg = getMessage();
        if (msg == null) {
            _headerSubject.setText("");
            _headerDate.setText("");
            _headerAuthor.setText("");
            _headerPages.removeAll();
            
            _headerSubject.setEnabled(false);
            _headerDate.setEnabled(false);
            _headerAuthor.setEnabled(false);
            _headerStatus.setEnabled(false);
            _headerPrivacy.setEnabled(false);
            _headerInfo.setEnabled(false);
            _headerPages.setEnabled(false);
            _headerActions.setEnabled(false);
        } else {
            String subject = msg.getSubject();
            if (subject == null)
                subject = "";
            _headerSubject.setText(subject);
            String date = Constants.getDate(msg.getMessageId());
            _headerDate.setText(date);
            long authorId = msg.getAuthorChannelId();
            ChannelInfo authorInfo = _client.getChannel(authorId);
            String author = null;
            if ( (authorInfo != null) && (authorInfo.getName() != null) )
                author = authorInfo.getName();
            else if (authorInfo != null)
                author = authorInfo.getChannelHash().toBase64().substring(0,6);
            else
                author = _uri.getScope().toBase64().substring(0,6);
            _headerAuthor.setText(author);
            _headerPages.removeAll();
            for (int i = 0; i < msg.getPageCount(); i++)
                _headerPages.add("Page " + (i+1));
            
            // msg.getAvatar()
            
            _headerSubject.setEnabled(true);
            _headerDate.setEnabled(true);
            _headerAuthor.setEnabled(true);
            _headerStatus.setEnabled(true);
            _headerPrivacy.setEnabled(true);
            _headerInfo.setEnabled(true);
            if (msg.getPageCount() > 1)
                _headerPages.setEnabled(true);
            else
                _headerPages.setEnabled(false);
            _headerActions.setEnabled(true);
            
            if (_headerPages.getItemCount() > 0)
                _headerPages.select(_page-1);
            _headerActions.select(0);
        }
        
        updateInfoMenu(msg);
        
        _header.pack();
        
        SyndieURI uri = _uri;
        if (_page != 1)
            uri = SyndieURI.createMessage(uri.getScope(), uri.getMessageId().longValue(), _page);
        _body.renderPage(new PageRendererSource(_client), uri);
        _root.layout(true);
    }
    
    private void updateInfoMenu(MessageInfo msg) {
        while (_headerInfoMenu.getItemCount() > 0)
            _headerInfoMenu.getItem(0).dispose();
        boolean infoFound = false;
        if (msg != null) {
            if (msg.getAttachmentCount() > 0) {
                infoFound = true;
                MenuItem attachmentMenu = new MenuItem(_headerInfoMenu, SWT.CASCADE);
                attachmentMenu.setText("attachments");
                Menu sub = new Menu(attachmentMenu);
                attachmentMenu.setMenu(sub);
                for (int i = 0; i < msg.getAttachmentCount(); i++) {
                    MenuItem item = new MenuItem(sub, SWT.PUSH);
                    Properties cfg = _client.getMessageAttachmentConfig(msg.getInternalId(), i);
                    int size = _client.getMessageAttachmentSize(msg.getInternalId(), i);
                    StringBuffer buf = new StringBuffer();
                    buf.append((i+1)).append(": ");
                    String name = null;
                    if (cfg != null)
                        name = cfg.getProperty(Constants.MSG_ATTACH_NAME);
                    //String desc = cfg.getProperty(Constants.MSG_ATTACH_DESCRIPTION);
                    if (name != null)
                        buf.append(name);
                    buf.append(" [size: ").append(size).append("]");
                    item.setText(buf.toString());
                    final SyndieURI uri = SyndieURI.createAttachment(msg.getScopeChannel(), msg.getMessageId(), i+1);
                    item.addSelectionListener(new SelectionListener() {
                        public void widgetDefaultSelected(SelectionEvent selectionEvent) { _attachmentPopup.showURI(uri); }
                        public void widgetSelected(SelectionEvent selectionEvent) { _attachmentPopup.showURI(uri); }
                    });
                }
                MenuItem item = new MenuItem(sub, SWT.PUSH);
                item.setText("Save all");
            }
            List refs = msg.getReferences();
            if ( (refs != null) && (refs.size() > 0) ) {
                infoFound = true;
                MenuItem refMenu = new MenuItem(_headerInfoMenu, SWT.CASCADE);
                refMenu.setText("references");
                Menu sub = new Menu(refMenu);
                refMenu.setMenu(sub);
                for (int i = 0; i < refs.size(); i++) {
                    ReferenceNode node = (ReferenceNode)refs.get(i);
                    addRef(node, sub);
                }
            }
        }
        if (infoFound)
            _headerInfo.setBackground(ColorUtil.getColor("green", null));
        else
            _headerInfo.setBackground(null);
    }
    private void addRef(ReferenceNode node, Menu menu) {
        MenuItem item = null;
        if (node.getChildCount() > 0) {
            item = new MenuItem(menu, SWT.CASCADE);
            Menu sub = new Menu(item);
            item.setMenu(sub);
            for (int i = 0; i < node.getChildCount(); i++)
                addRef(node.getChild(i), sub);
        } else {
            item = new MenuItem(menu, SWT.PUSH);
        }
        
        if (node.getName() != null)
            item.setText(node.getName());
        else if (node.getDescription() != null)
            item.setText(node.getDescription());
        else if (node.getURI() != null)
            item.setText(node.getURI().toString()); // ugly!  make pretty.
        
        // adjust this listener depending upon the uri target
        item.addSelectionListener(new SelectionListener() {
            public void widgetSelected(SelectionEvent selectionEvent) {}
            public void widgetDefaultSelected(SelectionEvent selectionEvent) {}
        });
    }
    private void initComponents() {
        _root = new Composite(_parent, SWT.NONE);
        _root.setLayout(new GridLayout(1, false));
        
        _header = new Composite(_root, SWT.BORDER);
        GridData gd = new GridData(GridData.FILL, GridData.FILL, true, false);
        //gd.heightHint = 32;
        _header.setLayoutData(gd);
        _header.setLayout(new GridLayout(6, false));
        
        _headerAvatarDefault = _root.getDisplay().getSystemImage(SWT.ICON_QUESTION);
        _headerAvatar = _headerAvatarDefault;
        
        //_headerAvatarCanvas = new ImageCanvas(_header, false);
        //_headerAvatarCanvas.setImage(_headerAvatarDefault);
        //gd = new GridData(GridData.CENTER, GridData.CENTER, false, false);
        //_headerAvatarCanvas.forceSize(48,48);
        //_headerAvatarCanvas.setLayoutData(gd);
        
        _headerSubject = new Text(_header, SWT.BORDER | SWT.SINGLE | SWT.READ_ONLY);
        _headerSubject.setLayoutData(new GridData(GridData.FILL, GridData.FILL, true, true));
        _headerSubject.setText("");
        
        _headerDate = new Label(_header, SWT.NONE);
        _headerDate.setLayoutData(new GridData(GridData.FILL, GridData.CENTER, false, true));
        _headerAuthor = new Label(_header, SWT.NONE);
        _headerAuthor.setLayoutData(new GridData(GridData.FILL, GridData.CENTER, false, true));
        
        Composite metaInfo = new Composite(_header, SWT.NONE);
        metaInfo.setLayoutData(new GridData(GridData.END, GridData.CENTER, false, true));
        metaInfo.setLayout(new GridLayout(3, true));
        _headerStatus = new Label(metaInfo, SWT.BORDER);
        _headerStatus.setLayoutData(new GridData(12, 12));
        _headerPrivacy = new Label(metaInfo, SWT.BORDER);
        _headerPrivacy.setLayoutData(new GridData(12, 12));
        _headerInfo = new Label(metaInfo, SWT.BORDER);
        _headerInfo.setLayoutData(new GridData(12, 12));
        _headerInfoMenu = new Menu(_headerInfo);
        _headerInfo.setMenu(_headerInfoMenu);
        _headerInfo.addMouseListener(new MouseListener() {
            public void mouseDoubleClick(MouseEvent mouseEvent) { _headerInfoMenu.setVisible(true); }
            public void mouseDown(MouseEvent mouseEvent) { _headerInfoMenu.setVisible(true); }
            public void mouseUp(MouseEvent mouseEvent) { _headerInfoMenu.setVisible(true); }
        });
        
        _headerPages = new Combo(_header, SWT.DROP_DOWN | SWT.READ_ONLY);
        _headerPages.setLayoutData(new GridData(GridData.FILL, GridData.FILL, false, false));
        _headerPages.addSelectionListener(new SelectionListener() {
            public void widgetDefaultSelected(SelectionEvent selectionEvent) { showPage(); }
            public void widgetSelected(SelectionEvent selectionEvent) { showPage(); }
        });
        
        _headerActions = new Combo(_header, SWT.DROP_DOWN | SWT.READ_ONLY);
        _headerActions.setLayoutData(new GridData(GridData.FILL, GridData.FILL, false, false));
        
        _headerActions.add("Action");
        _headerActions.add("View");
        _headerActions.add("Reply");
        _headerActions.add("Reply to author");
    
        _attachmentPopup = new AttachmentPreviewPopup(_client, _root.getShell());
        
        _body = new PageRenderer(_root, true);
        _body.setLayoutData(new GridData(GridData.FILL, GridData.FILL, true, true));
        _body.setListener(new PageRenderer.PageActionListener() {
            public void viewScopeMessages(PageRenderer renderer, Hash scope) {}
            public void viewScopeMetadata(PageRenderer renderer, Hash scope) {}
            public void view(PageRenderer renderer, SyndieURI uri) {}
            public void bookmark(PageRenderer renderer, SyndieURI uri) {}
            public void banScope(PageRenderer renderer, Hash scope) {}
            public void viewImage(PageRenderer renderer, Image img) {}
            public void ignoreImageScope(PageRenderer renderer, Hash scope) {}
            public void importReadKey(PageRenderer renderer, Hash referencedBy, Hash keyScope, SessionKey key) {}
            public void importPostKey(PageRenderer renderer, Hash referencedBy, Hash keyScope, SigningPrivateKey key) {}
            public void importManageKey(PageRenderer renderer, Hash referencedBy, Hash keyScope, SigningPrivateKey key) {}
            public void importReplyKey(PageRenderer renderer, Hash referencedBy, Hash keyScope, PrivateKey key) {}
            public void importArchiveKey(PageRenderer renderer, Hash referencedBy, SyndieURI archiveURI, SessionKey key) {}
            public void saveAllImages(PageRenderer renderer, Map images) {}
            public void saveImage(PageRenderer renderer, String suggestedName, Image img) {}
            public void privateReply(PageRenderer renderer, Hash author, SyndieURI msg) {}
            public void replyToForum(PageRenderer renderer, Hash forum, SyndieURI msg) {}
        });
    }
}
