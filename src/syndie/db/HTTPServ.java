package syndie.db;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Properties;
import net.i2p.data.Base64;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.SessionKey;
import net.i2p.util.SocketTimeout;
import syndie.Constants;

/**
 * CLI parameters: ([--port $num] [--listeners $num] [--writable true] | [--kill true])
 */
public class HTTPServ implements CLI.Command {
    private static ServerSocket _ssocket;
    private List<Runner> _runners;
    /** accepted Socket instances that haven't run yet */
    private static final List<Socket> _pendingSockets = new ArrayList();
    private DBClient _client;
    private static UI _ui;
    private static boolean _alive = false;
    private static boolean _starting = false;
    private static boolean _startFailed = false;
    private static String _startFailedMessage;
    private boolean _allowPost;
    private static SharedArchive _archive;
    private int _minListeners;
    private int _curListeners;
    private static final int MAX_LISTENERS = 50;
    private static boolean _rebuilding;
    private HashMap<String, File> _sharedFiles;
    
    private static final boolean REJECT_INPROXY = true;

    public HTTPServ() {
        if (isAlive()) return;
        _runners = new ArrayList();
        _client = null;
        _allowPost = false;
        _startFailed = false;
        _startFailedMessage = null;
        _starting = true;
        _rebuilding = false;
    }
    public HTTPServ(DBClient client, UI ui, int listenPort, int minListeners, boolean writable) {
        if (isAlive()) return;
        
        _client = client;
        _ui = ui;
        _startFailed = false;
        _startFailedMessage = null;
        _starting = true;
        if (listenPort <= 0)
            listenPort = 8080;
        if (minListeners <= 0)
            minListeners = 5;
        else if (minListeners > MAX_LISTENERS)
            minListeners = MAX_LISTENERS;
        
        _minListeners = minListeners;
        _allowPost = writable;
        
        startup(listenPort);
    }
    
    public HTTPServ(DBClient client, UI ui) {
        this(client, ui, getListenPort(client), getMinListeners(client), getWritable(client));
    }
    
    public static int getListenPort(DBClient client) {
        Properties prefs = client.getNymPrefs();
        String portStr = prefs.getProperty("httpserv.port");
        if (portStr != null) {
            try {
                return Integer.parseInt(portStr);
            } catch (NumberFormatException nfe) {}
        }
        return 8080;
    }
    public static boolean getWritable(DBClient client) {
        Properties prefs = client.getNymPrefs();
        boolean writable = false;
        String writableStr = prefs.getProperty("httpserv.writable");
        if (writableStr != null)
            writable = Boolean.valueOf(writableStr).booleanValue();
        return writable;
    }
    public static int getMinListeners(DBClient client) {
        Properties prefs = client.getNymPrefs();
        String minStr = prefs.getProperty("httpserv.minListeners");
        if (minStr != null) {
            try {
                return Integer.parseInt(minStr);
            } catch (NumberFormatException nfe) {}
        }
        return 5;
    }
    
    public static boolean startFailed() { return _startFailed; }
    public static boolean startInProgress() { return _starting; }
    
    public static void killAll() { 
        _alive = false;
        if (_ui != null)
            _ui.debugMessage("Marking server as dead");
        synchronized (_pendingSockets) { 
            _pendingSockets.notifyAll(); 
        }
        try {
            if (_ssocket != null) {
                if (_ui != null)
                    _ui.debugMessage("Closing server socket");
                _ssocket.close();
            }
        } catch (IOException ioe) {
            if (_ui != null)
                _ui.debugMessage("Problem closing server socket", ioe);
        }
        _ssocket = null;
    }
    public static boolean isAlive() { return _alive; }

    public DBClient runCommand(Opts opts, UI ui, DBClient client) {
        _client = client;
        _ui = ui;
        if (opts.getOptBoolean("kill", false)) {
            killAll();
            _ui.statusMessage("HTTP server killed");
            _ui.commandComplete(0, null);
            return client;
        }
        
        int port = (int)opts.getOptLong("port", 8080);
        _minListeners = (int)opts.getOptLong("listeners", 5);
        _allowPost = opts.getOptBoolean("writable", true);
        if (startup(port)) {
            ui.statusMessage("HTTP archive server listening on " + port);
            ui.commandComplete(0, null);
        } else {
            ui.commandComplete(-1, null);
        }
        return client;
    }
    
    private void addSharedFile(String uri, File file) {
        _ui.statusMessage("Serving file \"" + file.toString() + "\" from uri \"" + uri + "\" via HTTP");
        _sharedFiles.put(uri, file);
    }
    private void buildSharedFiles() {
        _sharedFiles = new HashMap();
        
        File archiveDir = _client.getArchiveDir();
        File distDir = new File(_client.getRootDir(), "dist");
        String sharedIndex = LocalArchiveManager.SHARED_INDEX_FILE;
        
        addSharedFile("/index.html", new File(archiveDir, "index.html"));
        addSharedFile("/" + sharedIndex, new File(archiveDir, sharedIndex));
        
        if (distDir.isDirectory()) {
            String [] dist = distDir.list();
            for (int c = 0; c < dist.length; c++)
                addSharedFile("/dist/" + dist[c], new File(distDir, dist[c]));
        }
    }
    
    private boolean startup(int port) {
        buildSharedFiles();
        
        try {
            _ssocket = new ServerSocket(port);
            _alive = true;
            _ui.debugMessage("Set server socket to " + _ssocket);
            for (int i = 0; i < _minListeners; i++) {
                Thread t = new Thread(new Runner(), "HTTPServ run " + i);
                t.setDaemon(true);
                t.start();
            }
            Thread t = new Thread(new AcceptRunner(), "HTTPServ accept");
            t.setDaemon(true);
            t.start();
            _starting = false;
            return true;
        } catch (IOException ioe) {
            _ui.errorMessage("error listening to " + port, ioe);
            _startFailedMessage = ioe.getMessage();
            _startFailed = true;
            _starting = false;
            return false;
        }
    }
    public static String getStartFailedMessage() { return _startFailedMessage; }
    public static void clearFailedMessage() { _startFailedMessage = null; }
    
    private static final int MAX_PENDING = 10;
    
    private class AcceptRunner implements Runnable {
        public void run() {
            boolean loggedIn = false;
            while (_alive && _ssocket != null) {
                if (!_client.isLoggedIn()) {
                    if (loggedIn) {
                        _ui.debugMessage("Stopping - logged out of database");
                        break; // if we logged in but then logged out, stop
                    }
                    _ui.debugMessage("Waiting for database login...");
                    try { Thread.sleep(1000); } catch (InterruptedException ie) {}
                    continue;
                }

                final SyncManager mgr;
                try {
                    // we can get past the above check and yet still throw an ISE here...
                    // loop around again if we did
                    mgr = SyncManager.getInstance(_client, _ui);
                } catch (IllegalStateException ise) {
                    // "Not logged in"
                    if (!loggedIn) {
                        _ui.debugMessage("Waiting for database login (ISE)...");
                        try { Thread.sleep(1000); } catch (InterruptedException ie) {}
                    }
                    continue;
                }
                if (!loggedIn) {
                    _ui.debugMessage("Starting acceptance runner");
                    loggedIn = true;
                }

                final File sharedIndex = new File(_client.getArchiveDir(), LocalArchiveManager.SHARED_INDEX_FILE);

                //SyndicationManager manager = SyndicationManager.getInstance(_client, _ui);
                //manager.loadArchives();
                if (!sharedIndex.exists()) {
                    _ui.debugMessage("shared index does not exist: building it");
                    LocalArchiveManager.buildIndex(_client, _ui, mgr.getDefaultPullStrategy());
                } else if (!_rebuilding && (sharedIndex.lastModified() + LocalArchiveManager.getLocalRebuildDelayHours(_client)*60*60*1000L < System.currentTimeMillis())) {
                    _rebuilding = true;
                    _ui.debugMessage("shared index is too old, rebuilding it");
                    JobRunner.instance().enqueue(new Runnable() { 
                        public void run() { 
                            File tmp = null;
                            FileInputStream fis = null;
                            FileOutputStream fos = null;
                            try {
                                tmp = File.createTempFile("index", "dat", _client.getTempDir());
                                LocalArchiveManager.buildIndex(_client, _ui, mgr.getDefaultPullStrategy(), tmp);
                                fis = new FileInputStream(tmp);
                                fos = new FileOutputStream(sharedIndex);
                                byte buf[] = new byte[1024*16];
                                int read = -1;
                                while ( (read = fis.read(buf)) != -1)
                                    fos.write(buf, 0, read);
                                fos.close();
                                fis.close();
                                fis = null;
                                fos = null;
                                tmp.delete();
                                tmp = null;
                            } catch (Exception e) {
                                _ui.errorMessage("Error rebuilding", e);
                            } finally {
                                if (fis != null) try { fis.close(); } catch (IOException ioe) {}
                                if (fos != null) try { fos.close(); } catch (IOException ioe) {}
                                if (tmp != null) tmp.delete();
                            }
                            _rebuilding = false;
                        }
                    });                    
                }
                
                try {
                    // we want to break from the accept() periodically so we can do the above rebuilding checks
                    _ssocket.setSoTimeout(2*60*1000);
                    Socket socket = _ssocket.accept();
                    _ui.debugMessage("Connection accepted");
                    boolean added = false;
                    boolean createNewHandler = false;
                    synchronized (_pendingSockets) {
                        if (_pendingSockets.size() < MAX_PENDING) {
                            _pendingSockets.add(socket);
                            added = true;
                            
                            if (_client.ctx().random().nextInt(MAX_PENDING) <= _pendingSockets.size()) {
                                if (_curListeners < MAX_LISTENERS)
                                    createNewHandler = true;
                            }
                        }
                        _pendingSockets.notifyAll();
                    }
                    if (createNewHandler) {
                        Thread t = new Thread(new Runner(true), "HTTPServ run [on demand]");
                        t.setDaemon(true);
                        t.start();
                    }
                    if (!added)
                        tooBusy(socket);
                } catch (InterruptedIOException iie) {
                    // ignore the accept() timeout
                } catch (IOException ioe) {
                    if (_alive)
                        _ui.debugMessage("Error accepting", ioe);
                }
            }
            _ui.debugMessage("Accept runner terminated");
        }
    }
    
    private class Runner implements Runnable {
        private boolean _isDynamic;
        public Runner() { this(false); }
        public Runner(boolean isDynamic) { 
            _isDynamic = isDynamic; 
            _curListeners++;
        }
        public void run() {
            while (_alive) {
                try {
                    Socket socket = null;
                    synchronized (_pendingSockets) {
                        if (_pendingSockets.size() <= 0) {
                            if (_isDynamic) {
                                _ui.debugMessage("Terminating dynamic HTTP runner as the pending queue is clear");
                                _curListeners--;
                                return;
                            }
                            _pendingSockets.wait();
                        } else {
                            socket = (Socket)_pendingSockets.remove(0);
                        }
                    }
                    if (socket != null)
                        handle(socket);
                } catch (IOException ioe) {
                    _ui.debugMessage("Error handing socket", ioe);
                } catch (InterruptedException ie) {}
            }
            _ui.debugMessage("Terminating runner " + Thread.currentThread().getName());
            _curListeners--;
        }
    }
    
    private void handle(Socket socket) throws IOException {
        _ui.debugMessage("handling a client");
        String methodLine = null;

        SocketTimeout timeout = new SocketTimeout(socket, 60*1000);
        InputStream in = socket.getInputStream();
        OutputStream out = socket.getOutputStream();
        HashMap headers = new HashMap();
        
        methodLine = DataHelper.readLine(in);
        if (methodLine == null) {
            fail(socket, in, out, timeout);
            return;
        }
        
        // recv headers
        for (int c = 0; c < 50; c++) { // sanity check, don't keep recving headers forever
            String line = DataHelper.readLine(in);
            if (line == null) {
                fail(socket, in, out, timeout);
                return;
            } else if (line.trim().equals("")) // all headers recved
                break;
            
            String [] header = line.split(":", 2);
            // FIXME map keys to upper or lower case
            headers.put(header[0].trim(), header[1].trim());
        }
        
        try {
            if (methodLine.startsWith("GET "))
                handleGet(socket, in, out, timeout, getPath(methodLine), headers);
            else if (methodLine.startsWith("HEAD "))
                handleHead(socket, in, out, timeout, getPath(methodLine), headers);
            else if (methodLine.startsWith("POST "))
                handlePost(socket, in, out, timeout, getPath(methodLine), headers);
            else
                fail405(socket, in, out, timeout);
        } catch (RuntimeException re) {
            _ui.errorMessage("Error handling", re);
            fail(socket, in, out, timeout);
        }
    }
    
    private static final String getPath(String line) {
        int urlBegin = line.indexOf(' ') + 1;
        if (urlBegin > 0) {
            int urlEnd = line.indexOf(' ', urlBegin+1);
            if (urlEnd > 0) {
                String url = line.substring(urlBegin, urlEnd);
                if (url.startsWith("/")) {
                    return url.trim();
                } else {
                    try {
                        URI uri = new URI(url);
                        return uri.getPath();
                    } catch (URISyntaxException use) {
                        return null;
                    }
                }
            } else {
                return null;
            }
        } else {
            return null;
        }
    }
    private static final String getChannel(String path) {
        int idx = path.lastIndexOf('/');
        if (idx < 0) return null;
        if ( (path.charAt(0) == '/') && (idx > 1) )
            return path.substring(1, idx);
        else
            return path.substring(0, idx);
    }

    private static final String getChannelSub(String path) {
        int idx = path.lastIndexOf('/');
        if ( (idx < 0) || (idx + 1 >= path.length()) ) return null;
        return path.substring(idx+1);
    }

    private void handleGet(Socket socket, InputStream in, OutputStream out, SocketTimeout timeout, String path, HashMap headers) throws IOException {
        if (path == null || path.equals("/"))
            path = "/index.html";
        _ui.debugMessage("GET " + path);
        if (REJECT_INPROXY && path != "/index.html" &&
            (headers.containsKey("X-Forwarded-For") || headers.containsKey("X-Forwarded-Server"))) {
            fail403(socket, in, out, timeout);
            return;
        }
        
        File file = (File) _sharedFiles.get(path);
        if (file != null)
            send(socket, in, out, file, timeout);
        else {
            String chan = getChannel(path);
            String sub = getChannelSub(path);
            _ui.debugMessage("GET of [" + chan + "]  [" + sub + "]");
            sendIfAllowed(chan, sub, socket, in, out, timeout);
        }
    }
    
    /**
     *  Only handles the shared files, otherwise sends a 405
     *  @since 1.101b-8
     */
    private void handleHead(Socket socket, InputStream in, OutputStream out, SocketTimeout timeout, String path, HashMap headers) throws IOException {
        if (path == null || path.equals("/"))
            path = "/index.html";
        _ui.debugMessage("HEAD " + path);
        if (REJECT_INPROXY && path != "/index.html" &&
            (headers.containsKey("X-Forwarded-For") || headers.containsKey("X-Forwarded-Server"))) {
            fail403(socket, in, out, timeout);
            return;
        }
        
        File file = (File) _sharedFiles.get(path);
        if (file != null) {
            sendHeaders(socket, in, out, file, timeout);
            close(socket, in, out, timeout);
        } else {
            fail405(socket, in, out, timeout);
        }
    }
    
    private SharedArchive getSharedArchive() {
        File indexFile = new File(_client.getArchiveDir(), LocalArchiveManager.SHARED_INDEX_FILE);
        boolean needsLoad = false;
        if (_archive == null)
            needsLoad = true;
        else if (_archive.getLoadDate() < indexFile.lastModified())
            needsLoad = true;
        
        if (needsLoad && indexFile.exists()) {
            FileInputStream fin = null;
            try {
                fin = new FileInputStream(indexFile);
                SharedArchive archive = new SharedArchive();
                archive.read(fin);
                fin.close();
                fin = null;
                _archive = archive;
            } catch (IOException ioe) {
                _ui.errorMessage("Error loading the archive index", ioe);
            } finally {
                if (fin != null) try { fin.close(); } catch (IOException ioe) {}
            }
        }
        return _archive;
    }
    
    private void sendIfAllowed(String chan, String sub, Socket socket, InputStream in, OutputStream out, SocketTimeout timeout) throws IOException {
        // we only send a file if it is in our published shared archive index, which
        // doesn't necessarily contain everything we have (for anonymity reasons)
        SharedArchive archive = getSharedArchive();
        if (archive == null) {
            fail404(socket, in, out, timeout);
            return;
        }
        
        if (("meta" + Constants.FILENAME_SUFFIX).equals(sub)) {
            byte hash[] = Base64.decode(chan);
            if ( (hash != null) && (hash.length == Hash.HASH_LENGTH) ) {
                Hash chanHash = new Hash(hash);
                if (archive.getChannel(chanHash) != null) {
                    // ok, metadata is published, allow the send
                    send(socket, in, out, new File(new File(_client.getArchiveDir(), chan), "meta" + Constants.FILENAME_SUFFIX), timeout);
                    return;
                } else {
                    // we may even have it, but its not in our published index, so dont give it to them
                    fail404(socket, in, out, timeout);
                }
            } else {
                // bad channel name
                fail404(socket, in, out, timeout);
            }
        } else {
            byte hash[] = Base64.decode(chan);
            if ( (hash != null) && (hash.length == Hash.HASH_LENGTH) ) {
                Hash chanHash = new Hash(hash);
                long messageId = SharedArchiveBuilder.getMessageId(sub);
                if (messageId < 0) {
                    fail404(socket, in, out, timeout);
                } else if (archive.isKnown(chanHash, messageId)) {
                    // ok, message is published, allow the send
                    send(socket, in, out, new File(new File(_client.getArchiveDir(), chan), messageId + Constants.FILENAME_SUFFIX), timeout);
                    return;
                } else {
                    // we may even have it, but its not in our published index, so dont give it to them
                    fail404(socket, in, out, timeout);
                }
            } else {
                // bad channel name
                fail404(socket, in, out, timeout);
            }
        }
    }
    
    private void send(Socket socket, InputStream in, OutputStream out, File file, SocketTimeout timeout) throws IOException {
        sendHeaders(socket, in, out, file, timeout);
        sendBody(socket, in, out, file, timeout);
    }

    /**
     *  Send the HTTP headers
     *  @since 1.101b-8
     */
    private void sendHeaders(Socket socket, InputStream in, OutputStream out, File file, SocketTimeout timeout) throws IOException {
        String type = "application/octet-stream";
        String name = file.getName();
        if (name.endsWith(".html"))
            type = "text/html";
        else if (name.endsWith(".syndie"))
            type = "application/x-syndie";
        else if (name.endsWith(".dat"))
            type = "application/x-syndie-index";
        StringBuilder buf = new StringBuilder();
        buf.append("HTTP/1.0 200 OK\r\n");
        buf.append("Content-type: ").append(type).append("\r\n");
        buf.append("Content-length: ").append(file.length()).append("\r\n");
        buf.append("Connection: close\r\n");
        buf.append("\r\n");
        out.write(DataHelper.getUTF8(buf.toString()));
        
        timeout.resetTimer();
    }

    /**
     *  Send the HTTP body
     *  @since 1.101b-8
     */
    private void sendBody(Socket socket, InputStream in, OutputStream out, File file, SocketTimeout timeout) throws IOException {
        int len = 0;
        //Sha256Standalone hash = new Sha256Standalone();
        FileInputStream fin = null;
        try {
            fin = new FileInputStream(file);
            byte dbuf[] = new byte[4096];
            int read = 0;
            while ( (read = fin.read(dbuf)) != -1) {
                out.write(dbuf, 0, read);
                //hash.update(dbuf, 0, read);
                len += read;
                timeout.resetTimer();
            }
            timeout.resetTimer();
            
            fin.close();
            fin = null;
            
            out.flush();
            timeout.resetTimer();
            
            _ui.debugMessage("Sent " + file.getPath() + ": " + len + "/" + file.length());// +", sha256 = " + Base64.encode(hash.digest()));
        } finally {
            if (fin != null) try { fin.close(); } catch (IOException ioe) {}
            close(socket, in, out, timeout);
        }
    }
    
    private static void close(Socket socket, InputStream in, OutputStream out, SocketTimeout timeout) throws IOException {
        if (in != null) try { in.close(); in = null; } catch (IOException ioe) {}
        if (out != null) try { out.close(); out = null; } catch (IOException ioe) {}
        if (socket != null) try { socket.close(); socket = null; } catch (IOException ioe) {}
        if (timeout != null) {
            // No such method error here, caused by change in cancel() return type
            // from void to boolean in I2P 0.9.3. Recompile syndie to fix.
            // Catch the error here in case it's running with an old i2p.jar
            try {
                timeout.cancel();
            } catch (NoSuchMethodError e) {}
            timeout = null;         
        }
    }
    
    private void handlePost(Socket socket, InputStream in, OutputStream out, SocketTimeout timeout, String path, HashMap headers) throws IOException {
        if (!_allowPost) {
            fail403(socket, in, out, timeout);
            return;
        }
        _ui.debugMessage("handlePost");
        if (REJECT_INPROXY &&
            (headers.containsKey("X-Forwarded-For") || headers.containsKey("X-Forwarded-Server"))) {
            fail403(socket, in, out, timeout);
            return;
        }
        
        long remaining, contentLength;
        try {
            remaining = contentLength = Long.parseLong((String) headers.get("Content-length"));
        } catch (NumberFormatException nfe) {
            fail(socket, in, out, timeout);
            return;
        }
        
        File importDir = new File(_client.getTempDir(), System.currentTimeMillis() + "." + Thread.currentThread().hashCode() + ".imp");
        importDir.mkdirs();

        try {
            int headerSize = (int)DataHelper.readLong(in, 2);
            byte header[] = new byte[headerSize];
            int read = DataHelper.read(in, header);
            if (read != headerSize) {
                fail(socket, in, out, timeout);
                return;
            }

            remaining -= headerSize + 2;
            
            _ui.debugMessage("handlePost: header read, remaining: " + remaining);
            
            SessionKey authKey = getAuthorizationKey(header);
            SessionKey encKey = getEncryptionKey(header);
            if (!authorized(authKey)) {
                fail403(socket, in, out, timeout);
                return;
            }
            
            timeout.resetTimer();
            
            int msgNum = 0;
            while (remaining > 0) {
                msgNum++;
                int flags = (int)DataHelper.readLong(in, 1);
                long sz = DataHelper.readLong(in, 4);
                remaining -= 5;
                //if ( (sz > ArchiveIndex.DEFAULT_MAX_SIZE) || (sz > remaining{
                if (sz > remaining) {
                    _ui.debugMessage(msgNum + ": invalid size: " + sz + " remaining: " + remaining);
                    fail(socket, in, out, timeout);
                    return;
                }
                 
                remaining -= sz;
                
                byte msg[] = new byte[(int)sz];
                read = DataHelper.read(in, msg);
                if (read != (int)sz) {
                    fail(socket, in, out, timeout);
                    return;
                }
                
                if (sz > SharedArchive.DEFAULT_MAX_SIZE_KB*1024) {
                    _ui.debugMessage(msgNum + ": message size is too large: " + sz);
                    // ignore it
                } else {
                    if (remaining >= 0) {
                        // import it now?  queue it up for later?  see if its a dup?
                        FileOutputStream fos = new FileOutputStream(new File(importDir, msgNum + ".syndie"));
                        _ui.debugMessage(msgNum + ": handlePost: read message of size " + read + ", remaining: " + remaining);

                        fos.write(msg);
                        fos.close();
                    }
                }
                timeout.resetTimer();
            } 
            
            _ui.debugMessage(msgNum + ": handlePost: read complete " + contentLength + " to " + importDir.getPath());
            out.write(DataHelper.getUTF8("HTTP/1.0 200 OK\r\nConnection: close\r\n\r\n"));
            close(socket, in, out, timeout);
            if (msgNum > 0) {
                _ui.statusMessage("HTTP server received " + msgNum + " messages, scheduling bulk import");
                _ui.insertCommand("menu syndicate");
                _ui.insertCommand("bulkimport --dir '" + importDir.getPath() + "' --delete true --rmdir true");
                // the SyndicationManagerScheduler rebuilds our published index when its safe to do so
                //_ui.insertCommand("buildindex");
            }
        } catch (DataFormatException dfe) {
            delete(importDir);
            throw new IOException(dfe.getMessage());
        } catch (IOException ioe) {
            delete(importDir);
            throw ioe;
        }
    }
    
    /** key used to verify they're authorized to post */
    private SessionKey getAuthorizationKey(byte header[]) { return null; }
    /** key used to decrypt the content (to extract the .syndie files) */
    private SessionKey getEncryptionKey(byte header[]) { return null; }
    /** true if they're authorized to post */
    private boolean authorized(SessionKey authorizationKey) { return true; }
    
    private void delete(File dir) {
        File files[] = dir.listFiles();
        for (int i = 0; i < files.length; i++) {
            if (!files[i].getName().startsWith("."))
                files[i].delete();
        }
        dir.delete();
    }
    
    private void fail404(Socket socket, InputStream in, OutputStream out, SocketTimeout timeout) throws IOException {
        out.write(ERR_404);
        fail(socket, in, out, timeout);
    }
    private void fail403(Socket socket, InputStream in, OutputStream out, SocketTimeout timeout) throws IOException {
        out.write(ERR_403);
        fail(socket, in, out, timeout);
    }
    private void fail(Socket socket, InputStream in, OutputStream out, SocketTimeout timeout) throws IOException {
        _ui.debugMessage("failing socket", new Exception("source"));
        close(socket, in, out, timeout);
    }
    
    /** @since 1.101b-8 */
    private void fail405(Socket socket, InputStream in, OutputStream out, SocketTimeout timeout) throws IOException {
        out.write(ERR_405);
        fail(socket, in, out, timeout);
    }

    private static final byte[] TOO_BUSY = DataHelper.getUTF8("HTTP/1.0 401 TOO BUSY\r\nConnection: close\r\n\r\n<html><head><title>401 TOO BUSY</title></head><body><h1>401 TOO BUSY</h1></body></html>");
    private static final byte[] ERR_404 = DataHelper.getUTF8("HTTP/1.0 404 File not found\r\nConnection: close\r\n\r\n<html><head><title>404 File not found</title></head><body><h1>404 File not found</h1></body></html>");
    private static final byte[] ERR_403 = DataHelper.getUTF8("HTTP/1.0 403 Not authorized\r\nConnection: close\r\n\r\n<html><head><title>403 Not authorized</title></head><body><h1>403 Not authorized</h1></body></html>");
    /** @since 1.101b-8 */
    private static final byte[] ERR_405 = DataHelper.getUTF8("HTTP/1.0 405 Method not allowed\r\nConnection: close\r\n\r\n");
    
    private static final void tooBusy(Socket socket) throws IOException {
        SocketTimeout timeout = new SocketTimeout(socket, 20*1000);
        OutputStream out = socket.getOutputStream();
        out.write(TOO_BUSY);
        close(socket, socket.getInputStream(), out, timeout);
    }
}
