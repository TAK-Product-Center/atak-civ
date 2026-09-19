
package com.atakmap.app.preferences;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.RestrictionsManager;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.util.Base64;
import android.widget.Toast;

import com.atakmap.android.cot.CotMapComponent;
import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.android.preference.AtakPreferences;
import com.atakmap.app.ATAKActivity;
import com.atakmap.app.ATAKApplication;
import com.atakmap.app.R;
import com.atakmap.app.system.ResourceUtil;
import com.atakmap.comms.CotServiceRemote;
import com.atakmap.comms.CotServiceRemote.ConnectionListener;
import com.atakmap.comms.TAKServer;
import com.atakmap.coremap.filesystem.FileSystemUtils;
import com.atakmap.coremap.io.IOProviderFactory;
import com.atakmap.coremap.locale.LocaleUtil;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.xml.XMLUtils;
import com.atakmap.filesystem.HashingUtils;
import com.atakmap.net.AtakAuthenticationDatabase;
import com.atakmap.util.WildCard;
import com.atakmap.util.zip.IoUtils;

import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileFilter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringBufferInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import gov.tak.platform.lang.Parsers;

public class PreferenceControl implements ConnectionListener {

    static public final String TAG = "PreferenceControl";

    /**
     * App's current preference path (based on manifest package)
     */
    private final String DEFAULT_PREFERENCES_NAME;

    public final String[] PreferenceGroups;
    static private PreferenceControl _instance;
    private final Context _context;

    public static final String DIRNAME = FileSystemUtils.CONFIG_DIRECTORY
            + File.separatorChar + "prefs";
    public static final String DIRPATH = FileSystemUtils.getItem(DIRNAME)
            .getPath();

    private volatile CotServiceRemote _remote;
    private volatile boolean connected;

    private final AtakPreferences prefs;

    // LOAD_ALL_PREFERENCES, LOAD_CONNECTION_PREFERENCE, LOAD_OTHER_PREFERENCES
    public static final int LOAD_ALL_PREFERENCES = 0;
    public static final int LOAD_CONNECTION_PREFERENCES = 1;
    public static final int LOAD_OTHER_PREFERENCES = 2;

    public final Set<String> WriteOncePreferences = new CopyOnWriteArraySet<>();

    /**
     * Singleton class for exporting and importing external preferences into the system.
     */
    public static synchronized PreferenceControl getInstance(final Context c) {
        if (_instance == null) {
            _instance = new PreferenceControl(c);
        }

        return _instance;
    }

    public static synchronized void dispose() {
        if (_instance != null)
            _instance.disposeImpl();
        _instance = null;
    }

    private PreferenceControl(final Context context) {
        _context = context;
        connected = false;
        DEFAULT_PREFERENCES_NAME = _context.getPackageName() + "_preferences";
        PreferenceGroups = new String[] {
                "cot_inputs", "cot_outputs", "cot_streams",
                DEFAULT_PREFERENCES_NAME
        };
        prefs = AtakPreferences.getInstance(context);

        AtakBroadcast.getInstance().registerReceiver(br,
                new AtakBroadcast.DocumentedIntentFilter(
                        ATAKActivity.ONSTART_URI));

        AtakBroadcast.DocumentedIntentFilter restrictionsFilter = new AtakBroadcast.DocumentedIntentFilter(
                Intent.ACTION_APPLICATION_RESTRICTIONS_CHANGED);

        AtakBroadcast.getInstance().registerSystemReceiver(restrictionsReceiver,
                restrictionsFilter);

        WriteOncePreferences.add("exclusiveConnection");
    }

    /**
     * Preserves the original intent of connecting. Now that the PreferenceControl is started early,
     * allow for this to be called in the original location. This should not be called during the
     * ATAKActivity instantiation.
     */
    public void connect() {
        _remote = new CotServiceRemote();
        _remote.connect(this);
    }

    public void disconnect() {
        try {
            connected = false;
            if (_remote != null)
                _remote.disconnect();
            _remote = null;
        } catch (Exception e) {
            Log.e(TAG, "disconnection error: " + e);
        }
    }

    private void disposeImpl() {

        try {
            connected = false;
            if (_remote != null)
                _remote.disconnect();
            _remote = null;
        } catch (Exception e) {
            Log.e(TAG, "disconnection error occurred during shutdown: " + e);
        }
        AtakBroadcast.getInstance().unregisterReceiver(br);
        AtakBroadcast.getInstance()
                .unregisterSystemReceiver(restrictionsReceiver);

    }

    /**
     * Obtains all of the Shared Preferences used by the system.   Please note that the preference
     * do contain private device specific information.   If transferring to a new device please omit
     * the key "bestDeviceUID", if cloning please omit the key "bestDeviceUID" and "locationCallsign".
     * @return a hashmap containing the preference name as the key and the SharedPreference as a value.
     */
    public HashMap<String, SharedPreferences> getAllPreferences() {
        HashMap<String, SharedPreferences> prefs = new HashMap<>();
        for (String group : PreferenceGroups) {
            SharedPreferences pref = _context.getSharedPreferences(
                    group,
                    Context.MODE_PRIVATE);
            prefs.put(group, pref);
        }
        return prefs;
    }

    public void saveSettings(String path) {
        File configFile = new File(DIRPATH, path);
        if (!FileSystemUtils.deleteFile(configFile)) {
            Log.d(TAG, "error deleting: " + configFile);
        }
        boolean created = IOProviderFactory.createNewFile(configFile);
        if (!created) {
            Toast.makeText(_context,
                    R.string.preferences_text409,
                    Toast.LENGTH_SHORT).show();
            return;
        }

        StringBuilder sb = new StringBuilder(
                "<?xml version='1.0' standalone='yes'?>\r\n");
        sb.append("<preferences>\r\n");
        for (String PreferenceGroup : PreferenceGroups) {
            SharedPreferences pref = _context.getSharedPreferences(
                    PreferenceGroup,
                    Context.MODE_PRIVATE);

            sb.append("<preference version=\"1\" name=\"")
                    .append(PreferenceGroup).append("\">\r\n");
            Map<String, ?> keyValuePairs = pref.getAll();

            final String k1 = Base64.encodeToString(
                    AtakAuthenticationDatabase.TAG
                            .getBytes(FileSystemUtils.UTF8_CHARSET),
                    Base64.NO_WRAP);
            final String k2 = Base64.encodeToString(
                    AtakAuthenticationDatabase.TAG
                            .getBytes(FileSystemUtils.UTF8_CHARSET),
                    Base64.NO_WRAP);

            for (Map.Entry<String, ?> e : keyValuePairs.entrySet()) {
                String key = e.getKey();
                Object value = e.getValue();

                if (value == null) {
                    Log.d(TAG, "null value for key: " + key);
                    continue;
                }

                String strClass = value.getClass().toString();

                if (key.equals("locationCallsign") ||
                        key.equals("bestDeviceUID") ||
                        key.equals(k1) || key.equals(k2)) {
                    // do nothing
                } else {
                    sb.append("<entry key=\"");
                    sb.append(encode(key));
                    sb.append("\" class=\"");
                    sb.append(strClass);
                    sb.append("\">");

                    if (value instanceof Set<?>) {
                        // Store each entry in the set
                        sb.append("\r\n");
                        Set<?> set = (Set<?>) value;
                        for (Object v : set) {
                            sb.append("<element>");
                            if (v instanceof String)
                                v = encode((String) v);
                            sb.append(v);
                            sb.append("</element>\r\n");
                        }
                    } else {
                        if (value instanceof String)
                            value = encode((String) value);
                        sb.append(value);
                    }
                    sb.append("</entry>\r\n");
                }
            }

            sb.append("</preference>\r\n");

        }
        sb.append("</preferences>\r\n");

        try (BufferedWriter bw = new BufferedWriter(
                IOProviderFactory.getFileWriter(configFile))) {
            bw.write(sb.toString());
        } catch (IOException e) {
            Log.e(TAG, "error: ", e);
        }

    }

    /**
     * Loads a new default set of configuration options and then removes the file containing these
     * values. This is useful for staging a system once and then pushing configuration changes out
     * to many system post deployment. The file is named default and it is required to be in the
     * prefs directory. The prefs directory can either be internal or external. All other
     * preferences are loaded from just the internal card. XXX - This is a partial fix for the
     * staging of defaults to devices where ClockworkMod recovery is being used to stage the
     * devices.
     */
    public boolean ingestDefaults() {
        final String filename = "defaults";
        final String[] mounts = FileSystemUtils.findMountPoints();
        final AtakPreferences preferences = new AtakPreferences(_context);

        for (String mount : mounts) {
            File configFile = new File(mount + File.separator + DIRNAME
                    + File.separator
                    + filename);
            if (IOProviderFactory.exists(configFile)) {
                Log.d(TAG,
                        "default configuration file found, loading entries: "
                                + configFile);
                // do not perform a connection check
                try {
                    loadSettings(configFile);
                } catch (Exception e) {
                    Log.e(TAG,
                            "default configuration file contained an error: "
                                    + configFile);
                    Log.e(TAG, "error: ", e);
                }
                FileSystemUtils.deleteFile(configFile);
            } else {
                Log.d(TAG, "no default config file found: " + configFile);
            }
        }

        processEnterpriseConfigurationPreferences();

        // Scan the  device download directory for data packages matching a specific wildcard pattern
        new Thread("downloadDirectoryImport") {
            public void run() {

                final File downloadDir = Environment
                        .getExternalStoragePublicDirectory(
                                Environment.DIRECTORY_DOWNLOADS);

                processEnterpriseConfigurationDataPackage();

                Set<String> importedDpkg = preferences
                        .getStringSet("importedDpkg");
                if (importedDpkg == null)
                    importedDpkg = new HashSet<>();

                final File[] dpkgFiles = downloadDir
                        .listFiles(new FileFilter() {
                            @Override
                            public boolean accept(File f) {
                                return f.getName()
                                        .toLowerCase(LocaleUtil.getCurrent())
                                        .matches(WildCard.wildcardAsRegex(
                                                "atak*.dpk", '*'));
                            }
                        });

                if (dpkgFiles != null) {
                    for (File dpkgFile : dpkgFiles) {
                        String md5 = HashingUtils.md5sum(dpkgFile);
                        if (!importedDpkg.contains(md5)) {
                            File directory = FileSystemUtils
                                    .getItem(FileSystemUtils.TOOL_DATA_DIRECTORY
                                            + File.separator + "datapackage");

                            if (!directory.exists()) {
                                if (!directory.mkdirs()) {
                                    Log.e(TAG, "could not make the directory: "
                                            + directory);
                                }
                            }

                            final File outfile = new File(directory,
                                    dpkgFile.getName());

                            try {
                                FileSystemUtils.copyFile(dpkgFile, outfile);
                                FileSystemUtils.delete(dpkgFile);
                                importedDpkg.add(md5);
                            } catch (Exception ex) {
                                Log.e(TAG, "error importing " + dpkgFile + " : "
                                        + ex.getMessage());
                            }
                        }
                    }
                }
                preferences.set("importedDpkg", importedDpkg);
            }
        }.start();

        return true;
    }

    /**
     * Given a configuration file under the prefs directory, load the settings
     * @param path the filename for the preference file
     * @param bCheckConnection if the connection should be checked before loading the
     *                         preference file and toast as necessary.
     */
    public void loadSettings(String path, boolean bCheckConnection) {
        if (path.equals("<none>"))
            return;

        File configFile = new File(DIRPATH, path);
        if (!IOProviderFactory.exists(configFile)) {
            Log.w(TAG, "File not found: " + configFile.getAbsolutePath());
            ((Activity) _context).runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(_context, R.string.file_not_found,
                            Toast.LENGTH_SHORT)
                            .show();
                }
            });
            return;
        }
        if (bCheckConnection && !connected) {
            Log.w(TAG,
                    "System still loading.  Please try again: "
                            + configFile.getAbsolutePath());
            ((Activity) _context).runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(_context,
                            R.string.preferences_text410,
                            Toast.LENGTH_SHORT)
                            .show();
                }
            });
            return;
        }

        try {
            loadSettings(configFile);
        } catch (Exception e) {
            Log.e(TAG, "default configuration file contained an error: "
                    + configFile.getAbsolutePath(), e);
        }
    }

    /**
     * Loads the settings, and optionally performs a connection check to see if the
     * CotServiceRemote is running (leaving that in as legacy)
     * @return list of keys that were imported.
     */
    public List<String> loadSettings(final File configFile) {
        Log.d(TAG, "Loading settings: " + configFile.getAbsolutePath());

        try (InputStream is = IOProviderFactory.getInputStream(configFile)) {
            return loadSettings(is);
        } catch (SAXException e) {
            Log.e(TAG, "SAXException while parsing: " + configFile, e);
        } catch (IOException e) {
            Log.e(TAG, "IOException while parsing: " + configFile, e);
        } catch (ParserConfigurationException e) {
            Log.e(TAG, "ParserConfigurationException while parsing: "
                    + configFile, e);
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse: " + configFile, e);
        }

        return new ArrayList<>();
    }

    /**
     * Loads the settings from all categories, and optionally performs a connection check
     * to see if the CotServiceRemote is running (leaving that in as legacy)
     * @param is the input stream to process
     * @return list of keys that were imported.
     */
    public List<String> loadSettings(InputStream is) throws Exception {
        return loadSettings(is, LOAD_ALL_PREFERENCES);
    }

    /**
     * Loads the settings, and optionally performs a connection check to see if the
     * CotServiceRemote is running (leaving that in as legacy)
     * @param is the input stream to process
     * @param type the type to process (LOAD_ALL_PREFERENCES, LOAD_CONNECTION_PREFERENCE, LOAD_OTHER_PREFERENCES)
     * @return list of keys that were imported.
     */
    public List<String> loadSettings(InputStream is, int type)
            throws Exception {
        // Do file opening here

        final List<String> retval = new ArrayList<>();

        DocumentBuilderFactory dbf = XMLUtils.getDocumenBuilderFactory();
        DocumentBuilder db = dbf.newDocumentBuilder();
        Document doc = db.parse(is);

        ConnectionHolder[] connections = {
                new ConnectionHolder("cot_inputs", new HashMap<>()),
                new ConnectionHolder("cot_outputs", new HashMap<>()),
                new ConnectionHolder("cot_streams", new HashMap<>())
        };

        Node root = doc.getDocumentElement();
        NodeList preferences = root.getChildNodes();
        for (int i = 0; i < preferences.getLength(); i++) {
            Node preference = preferences.item(i);
            if (preference.getNodeName().equals("preference")) {

                String name = preference.getAttributes()
                        .getNamedItem("name").getNodeValue();
                Log.d(TAG, "name=" + name);

                switch (name) {
                    case "cot_inputs":
                        if (type == LOAD_ALL_PREFERENCES
                                || type == LOAD_CONNECTION_PREFERENCES)
                            loadConnectionHolder(connections[0], preference);
                        break;
                    case "cot_outputs":
                        if (type == LOAD_ALL_PREFERENCES
                                || type == LOAD_CONNECTION_PREFERENCES)
                            loadConnectionHolder(connections[1], preference);
                        break;
                    case "cot_streams":
                        if (type == LOAD_ALL_PREFERENCES
                                || type == LOAD_CONNECTION_PREFERENCES)
                            loadConnectionHolder(connections[2], preference);
                        break;
                    default:
                        if (type == LOAD_ALL_PREFERENCES
                                || type == LOAD_OTHER_PREFERENCES)
                            loadSettings(preference, name, retval);
                        break;
                }
            }
        }
        if (type == LOAD_ALL_PREFERENCES
                || type == LOAD_CONNECTION_PREFERENCES) {

            CotMapComponent cotMapComponent = CotMapComponent.getInstance();

            for (ConnectionHolder connection : connections) {
                Map<String, String> mapping = connection.getContents();
                if (mapping == null || mapping.isEmpty()) {
                    continue;
                }

                String countString = mapping.get("count");
                if (FileSystemUtils.isEmpty(countString)) {
                    continue;
                }
                int count = Integer.parseInt(countString);
                Log.d(TAG, "Loading " + count + " connections");
                for (int j = 0; j < count; j++) {
                    String description = mapping.get("description" + j);
                    String connectString = mapping
                            .get(TAKServer.CONNECT_STRING_KEY + j);
                    boolean enabled = Boolean
                            .parseBoolean(mapping.get("enabled" + j));

                    String strUseAuth = mapping.get("useAuth" + j);
                    boolean useAuth = strUseAuth != null && Boolean
                            .parseBoolean(strUseAuth);
                    String authMethod = mapping
                            .get(TAKServer.AUTH_METHOD_KEY + j);
                    if (FileSystemUtils.isEmpty(authMethod)) {
                        authMethod = "legacy";
                    }
                    String strCompress = mapping.get("compress" + j);
                    boolean compress = strCompress != null && Boolean
                            .parseBoolean(strCompress);
                    String cacheCreds = mapping.get("cacheCreds" + j);
                    if (!FileSystemUtils.isEmpty(cacheCreds)) {
                        // bring it to english if it was previous the current language
                        if (cacheCreds.equals(
                                _context.getString(R.string.cache_creds_both)))
                            cacheCreds = ResourceUtil.getString(_context,
                                    R.string.cache_creds_both, Locale.US);
                        else if (cacheCreds.equals(
                                _context.getString(
                                        R.string.cache_creds_username)))
                            cacheCreds = ResourceUtil.getString(_context,
                                    R.string.cache_creds_username, Locale.US);
                    }

                    String caPassword = mapping.get("caPassword" + j);
                    String clientPassword = mapping.get("clientPassword" + j);
                    String caLocation = mapping.get("caLocation" + j);
                    String certificateLocation = mapping
                            .get("certificateLocation"
                                    + j);

                    boolean enrollForCertificateWithTrust = Boolean
                            .parseBoolean(mapping
                                    .get("enrollForCertificateWithTrust" + j));

                    String exp = mapping.get(TAKServer.EXPIRATION_KEY + j);
                    long expiration = exp == null ? -1 : Long.parseLong(exp);

                    Bundle data = new Bundle();
                    data.putString("description", description);
                    data.putBoolean("enabled", enabled);
                    data.putBoolean("useAuth", useAuth);
                    data.putString(TAKServer.AUTH_METHOD_KEY, authMethod);
                    data.putBoolean("compress", compress);
                    data.putString("cacheCreds", cacheCreds);

                    data.putString("caPassword", caPassword);
                    data.putString("clientPassword", clientPassword);
                    data.putString("caLocation", caLocation);
                    data.putString("certificateLocation", certificateLocation);

                    data.putBoolean("enrollForCertificateWithTrust",
                            enrollForCertificateWithTrust);

                    if (mapping.get("enrollUseTrust" + j) != null) {
                        data.putBoolean("enrollUseTrust",
                                Boolean.parseBoolean(
                                        mapping.get("enrollUseTrust" + j)));
                    }

                    data.putLong(TAKServer.EXPIRATION_KEY, expiration);

                    if (cotMapComponent != null) {
                        Log.d(TAG, "Loading " + connection.getName()
                                + " connection " + connectString);
                        switch (connection.getName()) {
                            case "cot_inputs":
                                cotMapComponent.getCotServiceRemote().addInput(
                                        connectString, data);
                                break;
                            case "cot_outputs":
                                cotMapComponent.getCotServiceRemote().addOutput(
                                        connectString, data);
                                break;
                            case "cot_streams":
                                cotMapComponent.getCotServiceRemote().addStream(
                                        connectString, data);
                                break;
                        }
                    } else {
                        Log.d(TAG,
                                "CotMapComponent not yet loaded, using temp pref storage for "
                                        + connection.getName() + " connection "
                                        + connectString);
                        saveInputOutput(connection.getName(), connectString,
                                data);
                    }
                }
            }
        }

        Intent prefLoaded = new Intent();
        prefLoaded.setAction("com.atakmap.app.PREFERENCES_LOADED");
        AtakBroadcast.getInstance().sendBroadcast(prefLoaded);
        return retval;
    }

    private void loadSettings(Node preference, String name,
            List<String> retval) {
        if (name.equals("com.atakmap.app_preferences")
                || name.equals("com.atakmap.civ_preferences")
                || name.equals("com.atakmap.fvey_preferences")) {

            name = DEFAULT_PREFERENCES_NAME;

            //import legacy prefs using current package
            Log.d(TAG, "Fixing up baseline prefs: " + name);
        }

        SharedPreferences pref = _context.getSharedPreferences(name,
                Context.MODE_PRIVATE);
        Editor editor = pref.edit();

        NodeList items = preference.getChildNodes();
        //Log.d(TAG, "#items=" + items.getLength());
        for (int i = 0; i < items.getLength(); i++) {
            Node entry = items.item(i);

            // Skip non-entry elements
            if (!entry.getNodeName().equals("entry"))
                continue;

            String key = decode(entry.getAttributes()
                    .getNamedItem("key").getNodeValue());

            if (WriteOncePreferences.contains(key) && pref.contains(key)) {
                Log.d(TAG, "Skipping WriteOncePreference " + key);
                continue;
            }

            String value = "";
            Node firstChild;
            if ((firstChild = entry
                    .getFirstChild()) != null)
                value = firstChild.getNodeValue();

            //Log.d(TAG, "key=" + key + " val=" + value);

            String className = entry.getAttributes()
                    .getNamedItem("class")
                    .getNodeValue();

            switch (className) {
                case "class java.lang.String":
                    editor.putString(key, decode(value));
                    retval.add(key);
                    break;
                case "class java.lang.Boolean":
                    editor.putBoolean(key, Boolean.parseBoolean(value));
                    retval.add(key);
                    break;
                case "class java.lang.Integer":
                    editor.putInt(key, Integer.parseInt(value));
                    retval.add(key);
                    break;
                case "class java.lang.Float":
                    editor.putFloat(key, Float.parseFloat(value));
                    retval.add(key);
                    break;
                case "class java.lang.Long":
                    editor.putLong(key, Long.parseLong(value));
                    retval.add(key);
                    break;
                default: {
                    // Special handling for string sets
                    if (isSetClass(className)) {
                        NodeList elements = entry.getChildNodes();
                        Set<String> valueSet = new HashSet<>();
                        for (int j = 0; j < elements.getLength(); j++) {
                            Node el = elements.item(j);
                            if (!el.getNodeName().equals("element"))
                                continue;
                            firstChild = el.getFirstChild();
                            if (firstChild != null)
                                valueSet.add(decode(firstChild.getNodeValue()));
                        }
                        editor.putStringSet(key, valueSet);
                        retval.add(key);
                    }
                    break;
                }
            }

            editor.apply();
        }
    }

    private int findSavedInputOutputIndex(SharedPreferences prefs,
            String connectString) {
        int index = -1;
        int count = prefs.getInt("count", 0);
        for (int i = 0; i < count; ++i) {
            if (connectString
                    .equals(prefs.getString(TAKServer.CONNECT_STRING_KEY + i,
                            ""))) {
                index = i;
                break;
            }
        }
        return index;
    }

    private void saveInputOutput(String prefsName, String connectString,
            Bundle data) {
        SharedPreferences prefs = _context.getSharedPreferences(prefsName,
                Context.MODE_PRIVATE);

        SharedPreferences.Editor editor = prefs.edit();
        int index = findSavedInputOutputIndex(prefs, connectString);
        if (index == -1) {
            index = prefs.getInt("count", 0);
            editor.putInt("count", index + 1);
            editor.putString(TAKServer.CONNECT_STRING_KEY + index,
                    connectString);
        }

        editor.putBoolean("enabled" + index,
                data.getBoolean("enabled", true));
        editor.putString("description" + index,
                data.getString("description"));
        editor.putBoolean("compress" + index,
                data.getBoolean("compress", false));

        editor.apply();
    }

    private void loadConnectionHolder(ConnectionHolder holder,
            Node preference) {
        NodeList items = preference.getChildNodes();
        Log.d(TAG, "connection #items=" + items.getLength());
        for (int j = 0; j < items.getLength(); j++) {
            Node entry = items.item(j);
            if (entry.getNodeName().equals("entry")) {
                String key = entry.getAttributes().getNamedItem("key")
                        .getNodeValue();
                String value = "";
                Node firstChild;
                if ((firstChild = entry.getFirstChild()) != null)
                    value = firstChild.getNodeValue();

                //Log.d(TAG, "connection key=" + key + " val=" + value);
                holder.getContents().put(key, value);
            }
        }
    }

    private static class ConnectionHolder {
        private final Map<String, String> _contents;
        private final String _name;

        ConnectionHolder(String name, Map<String, String> contents) {
            _name = name;
            _contents = contents;
        }

        public String getName() {
            return _name;
        }

        public Map<String, String> getContents() {
            return _contents;
        }
    }

    @Override
    public void onCotServiceConnected(Bundle fullServiceState) {
        connected = true;
    }

    @Override
    public void onCotServiceDisconnected() {
        connected = false;

    }

    /**
     * Check if the given classname implements {@link Set}
     * @param className Class name
     * @return True if the class implements {@link Set}
     */
    private static boolean isSetClass(String className) {
        try {
            if (className.startsWith("class "))
                className = className.substring(6);
            Class<?> clazz = Class.forName(className);
            return Set.class.isAssignableFrom(clazz);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * This method is private to enable easier backporting to prior versions.   This
     * encodes the following into the appropriate unicode
     * "   &quot;   \u0022
     * '   &apos;   \u0027
     * <   &lt;     \u003c
     * >   &gt;     \u003e
     * &   &amp;    \u0026
     * @param v the unencoded string
     * @return the encoded string
     */
    private String encode(final String v) {
        return v.replaceAll("\"", "\\\\u0022")
                .replaceAll("'", "\\\\u0027")
                .replaceAll("<", "\\\\u003c")
                .replaceAll(">", "\\\\u003e")
                .replaceAll("&", "\\\\u0026");
    }

    /**
     * This method is private to enable easier backporting to prior versions.   This
     * decodes the unicode into the appropriate value
     * "   &quot;   \u0022
     * '   &apos;   \u0027
     * <   &lt;     \u003c
     * >   &gt;     \u003e
     * &   &amp;    \u0026
     * @param v the encoded string
     * @return the unencoded string
     */
    private String decode(String v) {
        return v.replaceAll("\\\\u0022", "\"")
                .replaceAll("\\\\u0027", "'")
                .replaceAll("\\\\u003c", "<")
                .replaceAll("\\\\u003e", ">")
                .replaceAll("\\\\u0026", "&");
    }

    private String loadRestrictions(final String key) {
        RestrictionsManager manager = (RestrictionsManager) _context
                .getSystemService(Context.RESTRICTIONS_SERVICE);
        Bundle restrictions = manager.getApplicationRestrictions();
        Object value = restrictions.get(key);
        if (value instanceof String) {
            if (!FileSystemUtils.isEmpty((String) value)) {
                return (String) value;
            }
        }
        return null;
    }

    private final BroadcastReceiver br = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null)
                return;

            if (action.equals(ATAKActivity.ONSTART_URI)) {
                Uri u = intent.getParcelableExtra("uri");
                if (u != null && "com.atakmap.app/preference"
                        .equals(u.getHost() + u.getPath())) {
                    AlertDialog.Builder ad = new AlertDialog.Builder(_context);
                    ad.setTitle(R.string.preferences);
                    ad.setCancelable(false);
                    ad.setMessage(
                            _context.getString(R.string.apply_config_question,
                                    "an external source"));
                    ad.setPositiveButton(R.string.yes,
                            new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog,
                                        int which) {
                                    generateUserRequest(u);
                                }
                            });
                    ad.setNegativeButton(R.string.no,
                            new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog,
                                        int which) {
                                }
                            });
                    ATAKApplication.getCurrentActivity()
                            .runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    ad.show();
                                }
                            });
                }
            }
        }
    };

    /**
     * Responsible for processing URI "com.atakmap.app/preference" keys and values in the form of
     * repeating query parameters [keyPOSTFIX, typePOSTFIX, valuePOSTFIX] such as
     * tak://com.atakmap.app/preference?key1=displayRed&type1=boolean&value1=true&key2=displayGreen&type2=boolean&value2=true
     * @param u
     */
    private void generateUserRequest(Uri u) {
        Set<String> set = u.getQueryParameterNames();
        for (String key : set) {
            if (key.startsWith("key")) {
                final String postfix = key.replace("key", "");
                final String value = "value" + postfix;
                final String type = "type" + postfix;

                String prefKey = u.getQueryParameter(key);
                String prefType = u.getQueryParameter(type);
                String prefValue = u.getQueryParameter(value);

                if (prefKey == null || prefValue == null)
                    continue;

                if (key.equals("bestDeviceUID"))
                    continue;

                if (prefType == null) {
                    Log.e(TAG, "received a key request: " + prefKey
                            + " without a keyType, assuming string");
                    prefType = "string";
                }

                final String prefTypelc = prefType
                        .toLowerCase(LocaleUtil.getCurrent());

                switch (prefTypelc) {
                    case "string":
                        prefs.set(prefKey, prefValue);
                        break;
                    case "boolean":
                        prefs.set(prefKey,
                                Parsers.parseBoolean(prefValue, false));
                        break;
                    case "integer":
                        prefs.set(prefKey, Parsers.parseInt(prefValue, 0));
                        break;
                    case "long":
                        prefs.set(prefKey, Parsers.parseLong(prefValue, 0));
                        break;
                }
            }
        }
    }

    private final BroadcastReceiver restrictionsReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            processEnterpriseConfigurationPreferences();
            processEnterpriseConfigurationDataPackage();

        }
    };

    private synchronized void processEnterpriseConfigurationPreferences() {
        try {
            final String filename = "defaults";

            AtakPreferences preferences = AtakPreferences.getInstance(_context);
            final String ecp = loadRestrictions(
                    "enterpriseConfigurationPreferences");
            if (ecp != null) {
                String oldhash = preferences
                        .get("enterpriseConfigurationPreferencesMd5", "");
                String newhash = HashingUtils.md5sum(ecp);
                if (!oldhash.equals(newhash)) {
                    File f = null;
                    FileOutputStream fos = null;
                    try {
                        f = new File(_context.getFilesDir(), filename);
                        FileSystemUtils.copyStream(
                                new StringBufferInputStream(ecp),
                                fos = new FileOutputStream(f));
                        loadSettings(f);
                    } finally {
                        FileSystemUtils.deleteFile(f);
                        IoUtils.close(fos);
                    }
                    preferences.set("enterpriseConfigurationPreferencesMd5",
                            newhash);

                }
            }
        } catch (Exception e) {
            Log.e(TAG,
                    "error occurred loading enterpriseConfigurationPreferences",
                    e);
        }
    }

    private synchronized void processEnterpriseConfigurationDataPackage() {
        processEnterpriseConfigurationDataPackage(
                "enterpriseConfigurationDataPackage");
        processEnterpriseConfigurationDataPackage(
                "enterpriseConfigurationDataPackage2");
        processEnterpriseConfigurationDataPackage(
                "enterpriseConfigurationDataPackage3");
        processEnterpriseConfigurationDataPackage(
                "enterpriseConfigurationDataPackage4");
        processEnterpriseConfigurationDataPackage(
                "enterpriseConfigurationDataPackage5");

    }

    private synchronized void processEnterpriseConfigurationDataPackage(
            String key) {
        AtakPreferences preferences = AtakPreferences.getInstance(_context);

        final String ecdp = loadRestrictions(key);
        if (ecdp != null) {
            String oldhash = preferences
                    .get(key + "Md5", "");
            String newhash = HashingUtils.md5sum(ecdp);
            Log.d(TAG,
                    "encountered an enterprise configuration datapackage: new hash["
                            +
                            newhash
                            + "] and previously ingested old hash ["
                            + oldhash + "]");
            if (!oldhash.equals(newhash)) {
                FileOutputStream fos = null;
                try {
                    byte[] data = Base64.decode(ecdp, Base64.DEFAULT);
                    File directory = FileSystemUtils
                            .getItem(FileSystemUtils.TOOL_DATA_DIRECTORY
                                    + File.separator + "datapackage");
                    if (!directory.exists()) {
                        if (!directory.mkdirs()) {
                            Log.e(TAG, "could not make the directory: "
                                    + directory);
                        }
                    }
                    File f = new File(directory,
                            "enterprise_config-" + newhash + ".dpk");
                    FileSystemUtils.copyStream(
                            new ByteArrayInputStream(data),
                            fos = new FileOutputStream(f));

                } catch (Exception e) {
                    Log.e(TAG,
                            "error occurred loading enterpriseConfigurationDataPackage",
                            e);
                } finally {
                    IoUtils.close(fos);
                }
                preferences.set(key + "Md5", newhash);
            }
        }

    }
}
