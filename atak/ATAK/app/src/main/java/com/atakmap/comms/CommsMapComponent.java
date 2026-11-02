
package com.atakmap.comms;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.util.Pair;

import com.atakmap.android.contact.IndividualContact;
import com.atakmap.android.cot.CotMapComponent;
import com.atakmap.android.filesharing.android.service.WebServer;
import com.atakmap.android.http.rest.ServerVersion;
import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.android.ipc.AtakBroadcast.DocumentedIntentFilter;
import com.atakmap.android.maps.AbstractMapComponent;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.missionpackage.MissionPackagePreferenceListener;
import com.atakmap.android.missionpackage.MissionPackageReceiver;
import com.atakmap.android.missionpackage.http.MissionPackageDownloader;
import com.atakmap.android.network.ui.CredentialsPreference;
import com.atakmap.android.preference.AtakPreferences;
import com.atakmap.android.update.AppMgmtUtils;
import com.atakmap.android.util.ATAKConstants;
import com.atakmap.android.util.ATAKUtilities;
import com.atakmap.android.util.NotificationUtil;
import com.atakmap.app.BuildConfig;
import com.atakmap.app.CrashListener;
import com.atakmap.app.R;
import com.atakmap.app.preferences.NetworkConnectionPreferenceFragment;
import com.atakmap.commoncommo.CloudClient;
import com.atakmap.commoncommo.CloudIO;
import com.atakmap.commoncommo.CloudIOProtocol;
import com.atakmap.commoncommo.CoTDetailExtender;
import com.atakmap.commoncommo.CoTMessageListener;
import com.atakmap.commoncommo.CoTMessageType;
import com.atakmap.commoncommo.CoTPointData;
import com.atakmap.commoncommo.CoTSendFailureListener;
import com.atakmap.commoncommo.CoTSendMethod;
import com.atakmap.commoncommo.Commo;
import com.atakmap.commoncommo.CommoException;
import com.atakmap.commoncommo.CommoLogger;
import com.atakmap.commoncommo.Contact;
import com.atakmap.commoncommo.ContactPresenceListener;
import com.atakmap.commoncommo.EnrollmentIO;
import com.atakmap.commoncommo.EnrollmentIOUpdate;
import com.atakmap.commoncommo.EnrollmentStep;
import com.atakmap.commoncommo.InterfaceStatusListener;
import com.atakmap.commoncommo.MissionPackageIO;
import com.atakmap.commoncommo.MissionPackageReceiveStatusUpdate;
import com.atakmap.commoncommo.MissionPackageSendStatusUpdate;
import com.atakmap.commoncommo.MissionPackageTransferException;
import com.atakmap.commoncommo.MissionPackageTransferStatus;
import com.atakmap.commoncommo.NetInterface;
import com.atakmap.commoncommo.NetInterfaceAddressMode;
import com.atakmap.commoncommo.NetInterfaceErrorCode;
import com.atakmap.commoncommo.PhysicalNetInterface;
import com.atakmap.commoncommo.QuicInboundNetInterface;
import com.atakmap.commoncommo.SimpleFileIO;
import com.atakmap.commoncommo.SimpleFileIOStatus;
import com.atakmap.commoncommo.SimpleFileIOUpdate;
import com.atakmap.commoncommo.StreamingNetInterface;
import com.atakmap.commoncommo.StreamingTransport;
import com.atakmap.commoncommo.TcpInboundNetInterface;
import com.atakmap.comms.http.HttpUtil;
import com.atakmap.comms.missionpackage.MPReceiveInitiator;
import com.atakmap.comms.missionpackage.MPReceiver;
import com.atakmap.comms.missionpackage.MPSendListener;
import com.atakmap.comms.missionpackage.MPSendListener.UploadStatus;
import com.atakmap.coremap.cot.event.CotDetail;
import com.atakmap.coremap.cot.event.CotEvent;
import com.atakmap.coremap.filesystem.FileSystemUtils;
import com.atakmap.coremap.io.IOProviderFactory;
import com.atakmap.coremap.locale.LocaleUtil;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.net.AtakAuthenticationCredentials;
import com.atakmap.net.AtakAuthenticationDatabase;
import com.atakmap.net.AtakCertificateDatabaseIFace;
import com.atakmap.net.CertificateManager;
import com.atakmap.util.zip.IoUtils;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.UnknownServiceException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Vector;
import java.util.concurrent.ConcurrentLinkedQueue;

public class CommsMapComponent extends AbstractMapComponent implements
        CoTMessageListener, ContactPresenceListener, InterfaceStatusListener,
        CoTSendFailureListener, OnSharedPreferenceChangeListener,
        CrashListener, CommsProvider.ContactPresenceListener {
    private static final String TAG = "CommsMapComponent";

    private static CommsMapComponent _instance;
    private final NetworkManagerLite networkManagerLite = new NetworkManagerLite();

    private final int SCAN_WAIT = 45 * 1000; // 45 seconds

    /**
     *  Status of an import, either one of
     *     SUCCESS - no problems
     *     FAILURE - problems not importable.
     *     DEFERRED - waiting on another item, so try again at the end.
     *     IGNORE - no problems, but also not handled
     */
    public enum ImportResult {
        SUCCESS(1),
        FAILURE(3),
        DEFERRED(2),
        IGNORE(0);

        private final int priority;

        ImportResult(int priority) {
            this.priority = priority;
        }

        /**
         * Compare this import result with another and return the result with
         * higher priority. This is useful when performing multiple import
         * operations in a method where a single result is returned.
         * IGNORE < SUCCESS < DEFERRED < FAILURE
         *
         * @param other Other result
         * @return The result with higher priority
         */
        public ImportResult getHigherPriority(ImportResult other) {
            return this.priority >= other.priority ? this : other;
        }
    }

    /**
     * Interface for <code>AbstractInput</code> & <code>AbstractStreaming</code> to directly
     * process an event rather than using a <code>CotDispatcher</code>
     * <p>
     * If set, this is used prior rather than dispatching
     */
    public interface DirectCotProcessor {
        ImportResult processCotEvent(CotEvent event, Bundle extra);
    }

    /**
     * Interface for a single plugin to post process a CotEvent provided a list of uids.   
     * The specific use case is for using a NettWarrior system where the Cursor on Target 
     * message requires outgoing routing information not unlike what is being added for 
     * communication through a TAK Server.   Care should be taken not to break the outgoing 
     * CotEvent Object.
     */
    public interface PreSendProcessor {

        /**
         * Processes a CotEvent and a list of uids as the final step before the event is sent 
         * to the common communication library.
         * Please be warned that no attempt should be made send the CotEvent from this method 
         * and this should only be used for fixing up the CotEvent based on NW requirements.
         * @param event the event that has been created for sending.
         * @param uids the list of uids for sending.  Can be null for broadcast.
         */
        void processCotEvent(CotEvent event, String[] uids);
    }

    /**
     * Interface to receive callbacks on an in-progress enrollment operation.
     * See CommsMapComponent.enroll()
     */
    public interface EnrollmentListener {
        /**
         * The enrollment operation completed with an error. No further updates will come
         * for this enrollment.
         * @param error indicator of what type of error has caused the enrollment to fail
         */
        void onEnrollmentErrored(ErrorState error);

        /**
         * The enrollment operation has completed successfully.  The arguments contain the
         * certificate and trust stores from the enrollment operation. No further updates for this
         * enrollment operation will be issued.
         * @param clientCertStore pkcs12 format certificate store containing the client
         *                        certificate and ancillary information.  The store
         *                        is encrypted using the password provided at start of the enrollment
         *                        operation
         * @param enrollmentTrustStore pkcs12 format certificate store containing the CA
         *                             certificates that the server provided during the enrollment
         *                             response. The store is encrypted using the password
         *                             provided at start of the enrollment operation.  This may be
         *                             null if the server did not provide any CAs in the reply
         */
        void onEnrollmentCompleted(byte[] clientCertStore,
                byte[] enrollmentTrustStore);

        /**
         * The enrollment operation has completed private key generation successfully as part of
         * the overall enrollment process.  The enrollment process will continue after this update
         * and further updates will be provided.
         * @param key newly generated private key, in PEM format, encrypted using the password
         *            provided at the start of the enrollment operation
         */
        void onEnrollmentKeygenerated(byte[] key);

        /**
         * Various error type/reason indicators for the enrollment operation
         */
        enum ErrorState {
            /**
             * Failed to establish a connection to the server due to invalid hostname, port, or
             * other connection issue not covered by other specific errors. 
             */
            CONNECTION_FAILURE,

            /**
             * Authentication failed, most likely due to wrong username or password provided
             * at start of enrollment.
             */
            AUTH_ERROR,

            /**
             * The server identified itself using a certificate whose signature is not trusted
             * or the host identified in the certificate does not match the hostname we intended
             * to enroll with and hos``t verification was requested.
             */
            SERVER_NOT_TRUSTED,

            /**
             * All other non-specific errors
             */
            OTHER,

            /** The identity provider could not be reached. */
            IDP_UNREACHABLE,

            /** The browser sign-in was cancelled before completion. */
            BROWSER_CANCELLED,

            /** The sign-in response was invalid or expired. */
            TOKEN_INVALID,

            /** The signed-in account is not permitted on the TAK Server. */
            GROUP_DENIED,

            /** A secure connection could not be verified. */
            TLS_FAILURE,

            /** The TAK Server rejected the sign-in proof. */
            SERVER_REJECTED,

            /** The TAK Server did not provide usable SSO settings. */
            DISCOVERY_FAILED
        }
    }

    private MapView mapView;
    private CommsProvider commo;
    private CotService cotService;
    private DirectCotProcessor directProcessor;
    private PreSendProcessor preSendProcessor;
    private TAKServerListener takServerListener;
    private File httpsCertFile;

    // CoT messages received before the map components finished loading
    private boolean componentsLoaded;
    private final List<Pair<String, String>> deferredMessages = new ArrayList<>();

    // used for recording bi-directional communications to and from the system
    // should not be used for anything more than that.
    private final ConcurrentLinkedQueue<CommsLogger> loggers = new ConcurrentLinkedQueue<>();

    private final Set<String> hwAddressesIn;
    private final Set<String> hwAddressesOut;

    private static class InputPortInfo {
        TAKServer inputPort;
        int netPort;
        String mcast; // might be null for no mcast
        boolean isTcp;

        InputPortInfo(TAKServer input, int port, String mcastAddr) {
            inputPort = input;
            netPort = port;
            mcast = mcastAddr;
            isTcp = false;
        }

        InputPortInfo(TAKServer input, int port) {
            inputPort = input;
            netPort = port;
            mcast = null;
            isTcp = true;
        }
    }

    private static class OutputPortInfo {
        TAKServer outputPort;
        int netPort;
        String mcast;

        OutputPortInfo(TAKServer output, int port, String mcastAddr) {
            outputPort = output;
            netPort = port;
            mcast = mcastAddr;
        }
    }

    private static class CotDetailExtensionAdapter
            implements CoTDetailExtender {
        private final CotDetailExtension impl;
        private final String detailElementName;

        CotDetailExtensionAdapter(String detailElementName,
                CotDetailExtension takExt) {
            impl = takExt;
            this.detailElementName = detailElementName;
        }

        public byte[] encode(String cot) {
            try {
                return impl.encode(cot);
            } catch (Exception ex) {
                Log.e(TAG, "Error encoding cot detail extension for element "
                        + detailElementName, ex);
                return null;
            }
        }

        public String decode(byte[] enc) {
            try {
                return impl.decode(enc);
            } catch (Exception ex) {
                Log.e(TAG, "Error decoding cot detail extension for element "
                        + detailElementName, ex);
                return null;
            }
        }
    }

    private MasterMPIO mpio;
    private MasterFileIO masterFileIO;
    private MasterEnrollmentIO masterEnrollment;
    // Contains all ports, registered or not
    private final Map<String, InputPortInfo> inputPorts;
    // Only the addresses from the enabled inputs 
    private final Map<Integer, Set<String>> mcastAddrsByPort;

    private final Map<String, OutputPortInfo> outputPorts;
    private final Map<String, TAKServer> streamPorts;
    private final Map<String, String> streamKeys;
    private final Map<String, Integer> streamNotificationIds;

    private final Map<Integer, List<PhysicalNetInterface>> inputIfaces;
    private final Map<String, TcpInboundNetInterface> tcpInputIfaces;
    private final Map<String, List<PhysicalNetInterface>> broadcastIfaces;
    private final Map<String, StreamingNetInterface> streamingIfaces;

    private final Set<String> contactUids;
    private final ConcurrentLinkedQueue<CotServiceRemote.CotEventListener> cotEventListeners = new ConcurrentLinkedQueue<>();

    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback connectivityManagerCallback;
    private WifiManager.MulticastLock multicastLock;
    private WifiManager.WifiLock wifiLock;
    private WifiManager wifi;
    private Thread scannerThread;
    private boolean rescan = true;
    private boolean pppIncluded = false;
    private OutboundLogger outboundLogger;

    private volatile boolean nonStreamsEnabled;
    private volatile boolean filesharingEnabled;
    private volatile int localWebServerPort;
    private volatile int secureLocalWebServerPort;

    private static class Logger implements CommoLogger {
        private final String tag;

        Logger(String tag) {
            this.tag = tag;
        }

        @Override
        public synchronized void log(Level level, Type type, String s,
                LoggingDetail detail) {
            int priority;
            switch (level) {
                case DEBUG:
                    priority = Log.DEBUG;
                    break;
                case ERROR:
                    priority = Log.ERROR;
                    break;
                case INFO:
                    priority = Log.INFO;
                    break;
                case VERBOSE:
                    priority = Log.VERBOSE;
                    break;
                case WARNING:
                    priority = Log.WARN;
                    break;
                default:
                    priority = Log.INFO;
                    break;
            }
            Log.println(priority, tag, s);
        }
    }

    public CommsMapComponent() {
        inputIfaces = new HashMap<>();
        tcpInputIfaces = new HashMap<>();
        broadcastIfaces = new HashMap<>();
        streamingIfaces = new HashMap<>();
        contactUids = new HashSet<>();
        outputsChangedListeners = new HashSet<>();
        inputsChangedListeners = new HashSet<>();
        streamPorts = new HashMap<>();
        streamKeys = new HashMap<>();
        outputPorts = new HashMap<>();
        inputPorts = new HashMap<>();
        mcastAddrsByPort = new HashMap<>();
        streamNotificationIds = new HashMap<>();
        hwAddressesIn = new HashSet<>();
        hwAddressesOut = new HashSet<>();
    }

    @Override
    public void onCreate(Context context, Intent intent, MapView view) {
        httpsCertFile = new File(context.getFilesDir(), "httpscert.p12");
        commo = CommsProviderFactory.getProvider();

        this.outboundLogger = new OutboundLogger(context);
        loggers.add(this.outboundLogger);
        connectivityManager = null;
        try {
            Log.d(TAG,
                    "acquire the multicast lock so the wifi does not deep sleep");
            wifi = (WifiManager) context.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            multicastLock = wifi
                    .createMulticastLock("mulicastLock in CommsMapComponent");
            multicastLock.acquire();
            Log.d(TAG, "acquired multicast lock...");

            Log.d(TAG, "registering wifi monitoring callback");
            NetworkRequest nreq = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .build();
            connectivityManagerCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    super.onAvailable(network);
                    Log.d(TAG,
                            "wifi network (re)connected, reacquiring mcast lock");
                    multicastLock.release();
                    multicastLock.acquire();
                }
            };
            connectivityManager = (ConnectivityManager) context
                    .getApplicationContext()
                    .getSystemService(Context.CONNECTIVITY_SERVICE);
            connectivityManager.registerNetworkCallback(nreq,
                    connectivityManagerCallback);
            Log.d(TAG, "wifi monitoring callback registered");

        } catch (Exception e) {
            Log.d(TAG, "failed to acquired multicast lock...");
        }

        try {
            wifi = (WifiManager) context.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                wifiLock = wifi.createWifiLock(
                        WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                        "wifi_lock");
                wifiLock.acquire();
            }
            Log.d(TAG, "acquired wifi lock...");

        } catch (Exception e) {
            Log.d(TAG, "failed to acquired wifi lock...");
        }

        AtakPreferences prefs = AtakPreferences.getInstance(context);

        if (!prefs.contains("enableNonStreamingConnections")) {
            Log.d(TAG, "setting the default mesh networking state to " +
                    BuildConfig.MESH_NETWORK_DEFAULT);
            prefs.set("enableNonStreamingConnections",
                    BuildConfig.MESH_NETWORK_DEFAULT);
        }
        if (!prefs.contains("mockingOption")) {
            Log.d(TAG, "setting the default gps listening state to: " +
                    (BuildConfig.MESH_NETWORK_DEFAULT ? "WRGPS" : "LocalGPS"));
            prefs.set("mockingOption",
                    BuildConfig.MESH_NETWORK_DEFAULT ? "WRGPS" : "LocalGPS");
        }
        if (!prefs.contains("nonBluetoothLaserRangeFinder")) {
            Log.d(TAG,
                    "setting the default non-bluetooth laser range finder listening state to "
                            +
                            BuildConfig.MESH_NETWORK_DEFAULT);
            prefs.set("nonBluetoothLaserRangeFinder",
                    BuildConfig.MESH_NETWORK_DEFAULT);

        }

        if (!commo.isInitialized()) {
            mapView = view;
            nonStreamsEnabled = true;
            filesharingEnabled = false;
            localWebServerPort = Commo.MPIO_LOCAL_PORT_DISABLE;
            secureLocalWebServerPort = WebServer.DEFAULT_SECURE_SERVER_PORT;

            final String uid = view.getSelfMarker().getUID();
            final String callsign = view.getSelfMarker().getMetaString(
                    "callsign", "nocallsign");

            try {
                commo.init(context, new Logger(TAG + "Commo"), uid, callsign,
                        NetInterfaceAddressMode.NAME);
                commo.addCoTMessageListener(this);
                if ((commo instanceof DefaultCommsProvider)) {
                    ((DefaultCommsProvider) commo)
                            .addInterfaceStatusListener(this);
                }
                commo.addContactPresenceListener(this);
                commo.addCoTSendFailureListener(this);
                if (commo instanceof DefaultCommsProvider)
                    ((DefaultCommsProvider) commo)
                            .setStreamMonitorEnabled(prefs.get(
                                    "monitorServerConnections", true));
                if ((commo instanceof DefaultCommsProvider)) {
                    ((DefaultCommsProvider) commo)
                            .setMulticastLoopbackEnabled(prefs.get(
                                    "network_multicast_loopback", false));
                    ((DefaultCommsProvider) commo).setDestUidInsertionEnabled(
                            prefs.get("insertDestInDirectedCoT", false));
                }

                rekey();

                if (commo.hasFeature(
                        CommsProvider.CommsFeature.MISSION_PACK_SETTINGS)) {
                    // Adjust if below the minimum supported value.  Pre-commo support default
                    // was lower than the minimum supported value
                    int xferTimeout = MissionPackagePreferenceListener.getInt(
                            prefs.getSharedPrefs(),
                            MissionPackagePreferenceListener.filesharingTransferTimeoutSecs,
                            MissionPackageReceiver.DEFAULT_TRANSFER_TIMEOUT_SECS);
                    if (xferTimeout < MissionPackageReceiver.DEFAULT_TRANSFER_TIMEOUT_SECS) {
                        prefs.set(
                                MissionPackagePreferenceListener.filesharingTransferTimeoutSecs,
                                String.valueOf(
                                        MissionPackageReceiver.DEFAULT_TRANSFER_TIMEOUT_SECS));
                        xferTimeout = MissionPackageReceiver.DEFAULT_TRANSFER_TIMEOUT_SECS;
                    }

                    try {
                        commo.setMissionPackageNumTries(
                                MissionPackagePreferenceListener.getInt(
                                        prefs.getSharedPrefs(),
                                        MissionPackagePreferenceListener.fileshareDownloadAttempts,
                                        MissionPackageDownloader.DEFAULT_DOWNLOAD_RETRIES));
                        commo.setMissionPackageConnTimeout(
                                MissionPackagePreferenceListener.getInt(
                                        prefs.getSharedPrefs(),
                                        MissionPackagePreferenceListener.filesharingConnectionTimeoutSecs,
                                        MissionPackageReceiver.DEFAULT_CONNECTION_TIMEOUT_SECS));
                        commo.setMissionPackageTransferTimeout(xferTimeout);
                        commo.setMissionPackageHttpsPort(SslNetCotPort
                                .getServerApiPort(SslNetCotPort.Type.SECURE));
                        commo.setMissionPackageHttpPort(SslNetCotPort
                                .getServerApiPort(SslNetCotPort.Type.UNSECURE));

                    } catch (CommoException ex) {
                        // if these fail, ignore error - not fatal
                        Log.e(TAG,
                                "Unable to setup mission package parameters in preferences during startup - invalid values found");
                    }
                }
                if (commo instanceof DefaultCommsProvider) {
                    ((DefaultCommsProvider) commo).setEnableAddressReuse(true);
                }

                mpio = new MasterMPIO();
                commo.setupMissionPackageIO(mpio);
                // Do this now to force sync between commo state and our initial preference state
                mpio.reconfigFileSharing();

                masterFileIO = new MasterFileIO();
                masterEnrollment = new MasterEnrollmentIO();

                if (commo.hasFeature(CommsProvider.CommsFeature.FILE_IO)) {
                    commo.enableSimpleFileIO(masterFileIO);
                    if (commo instanceof DefaultCommsProvider)
                        ((DefaultCommsProvider) commo)
                                .enableEnrollment(masterEnrollment);
                }

                if (commo.hasFeature(CommsProvider.CommsFeature.VPN)) {
                    // check for the existance of the software on the MAGTAB
                    // com.kranzetech.tvpn
                    if (AppMgmtUtils.isInstalled(view.getContext(),
                            "com.kranzetech.tvpn")) {
                        Log.d(TAG,
                                "detected Tactical VPN by Kranze Technologies");
                        commo.setMagtabWorkaroundEnabled(true);
                    } else {
                        Log.d(TAG, "did not detected Tactical VPN software");
                    }
                }

                setQuicEnabled(prefs.get(
                        "network_quic_enabled", true));

                if (prefs.get("ppp0_highspeed_capable", false)) {
                    setPPPIncluded(true);
                }

                Log.d(TAG,
                        "initialized the common communication layer instance");
            } catch (CommoException e) {

                Log.d(TAG,
                        "failed initialized the common communication layer instance",
                        e);
                NotificationUtil.getInstance().postNotification(
                        31345,
                        NotificationUtil.GeneralIcon.NETWORK_ERROR.getID(),
                        NotificationUtil.RED,
                        context.getString(R.string.network_library_error),
                        context.getString(R.string.comms_error), null,
                        false);

            }

            // likely not needed but for semantic symmetry
            hwAddressesOut.clear();
            hwAddressesIn.clear();
            hwAddressesIn.addAll(scanInterfaces(true));
            hwAddressesOut.addAll(scanInterfaces(true));
            scannerThread = new Thread("CommsMapComponent iface scan") {
                @Override
                public void run() {
                    while (rescan) {
                        try {
                            Thread.sleep(SCAN_WAIT);
                        } catch (InterruptedException ignored) {
                        }
                        rescanInterfaces();
                    }
                }
            };
            scannerThread.setPriority(Thread.NORM_PRIORITY);
            scannerThread.start();

        }

        HttpUtil.setGlobalHttpPermissiveMode(
                prefs.get("httpClientPermissiveMode", false));

        setPreferStreamEndpoint(
                prefs.get("autoDisableMeshSAWhenStreaming", false));

        DocumentedIntentFilter filter = new DocumentedIntentFilter();
        filter.addAction(CredentialsPreference.CREDENTIALS_UPDATED);
        filter.addAction(
                NetworkConnectionPreferenceFragment.CERTIFICATE_UPDATED);
        this.registerReceiver(context, _credListener, filter);

        // Listen for when all the other map components have finished loading
        filter = new DocumentedIntentFilter(
                "com.atakmap.app.COMPONENTS_CREATED");
        registerReceiver(context, _componentsListener, filter);

        // listen for network changes from network monitor
        DocumentedIntentFilter networkFilter = new DocumentedIntentFilter();
        networkFilter.addAction(NetworkManagerLite.NETWORK_STATUS_CHANGED);
        networkFilter.addAction(NetworkManagerLite.NETWORK_LIST);
        AtakBroadcast.getInstance().registerSystemReceiver(networkManagerLite,
                networkFilter);
        AtakBroadcast.getInstance().sendSystemBroadcast(
                new Intent(NetworkManagerLite.LIST_NETWORK));

        if (commo.hasFeature(CommsProvider.CommsFeature.FILE_IO)) {
            commo.registerFileIOProvider(
                    new com.atakmap.comms.FileIOProvider());
        }

        // register after initialization to ensure that mpio is non-null
        prefs.registerListener(this);

        _instance = this;
        cotService = new CotService(context);

        // TAK server singleton for developer convenience
        this.takServerListener = new TAKServerListener(view);

        vpnMonitor.setPriority(Thread.MIN_PRIORITY);
        vpnMonitor.setName("vpnMonitor");
        vpnMonitor.start();

    }

    /**
     * Registers a FileIOProvider with Commo
     * @param provider The provider to register
     */
    public void registerFileIOProvider(FileIOProvider provider) {
        if (commo != null
                && commo.hasFeature(CommsProvider.CommsFeature.FILE_IO)) {
            commo.registerFileIOProvider(provider);
        }
    }

    /**
     * Unregisters a FileIOProvider from Commo
     * @param provider The provider to unregister
     */
    public void unregisterFileIOProvider(FileIOProvider provider) {
        if (commo != null
                && commo.hasFeature(CommsProvider.CommsFeature.FILE_IO)) {
            commo.unregisterFileIOProvider(provider);
        }
    }

    /**
     * Register a CotDetailExtension that implements a specific CoT detail
     * extension for TAK Protocol. The provided CotDetailExtension will be used
     * to encode any CoT XML message containing the provided
     * detailElement
     * that is being transmitted via binary TAK protocol representation, 
     * as well as to decode any binary TAK protocol message that contains
     * the extension identified by the provided extensionId.
     * The extensionId is expected to be registered with the TAK Protocol
     * central extension registry to handle the indicated detailElement.
     * The provided CotDetailExtension implementation will remain active
     * until unregistered.
     * Extensions must completely and fully encode/decode their registered portion of the CoT 
     * detail. See TAK protocol documentation on extensions for further detail.
     * <p>
     * Only one extension may be registered at any one time for a given extensionId or
     * given detailElement; attempts to register a second extension with same id or element name
     * will fail.
     * @param extensionId unique and centrally registered identifier for the
     *                    TAK protocol extension being implemented by 
     *                    extender. Must be positive.
     * @param detailElement name of the element node of the cot detail
     *                    the extension handles
     * @param extension CotDetailExtension to register
     * @return true if registered successfully, false if another extension has already been
     *          registered for the given id or element name
     */
    public boolean registerCotDetailExtension(int extensionId,
            String detailElement, CotDetailExtension extension) {
        try {
            commo.registerCoTDetailExtender(extensionId, detailElement,
                    new CotDetailExtensionAdapter(detailElement, extension));
            return true;
        } catch (CommoException ex) {
            Log.e(TAG, "Could not register CotDetailExtension for id "
                    + extensionId + ", element " + detailElement);
            return false;
        }
    }

    /**
     * Unregister any previously registered CotDetailExtension for the given
     * extensionId. If no such extension is registered, no action is taken. 
     * @param extensionId unique and centrally registered identifier for the
     *                    TAK protocol extension to be deregistered. Must be positive.
     */
    public void unregisterCotDetailExtension(int extensionId) {
        try {
            commo.deregisterCoTDetailExtender(extensionId);
        } catch (CommoException ex) {
            Log.e(TAG, "Could not unregister CotDetailExtension for id "
                    + extensionId);
        }
    }

    /**
     * Registers a communication logger with the communication map component.
     * Please note that doing could potentially have serious performance ramifications
     * on the rest of ATAK and this should only be done for the purposes of gathering
     * metric or information gathering.    The callback should be shortlived since it will 
     * cause a delay in the rest of the processing chain.
     * @param logger the implementation of the comms logger.
     */
    public void registerCommsLogger(CommsLogger logger) {
        if (logger == null)
            return;

        if (!loggers.contains(logger)) {
            Log.w(TAG, "CommsLogger has been registered with the system: "
                    + logger.getClass() +
                    "\n         *** This will likely impact system performance ***");
            loggers.add(logger);
        }
    }

    /**
     * Allows a communication logger to be removed from the communication map component.
     */
    public void unregisterCommsLogger(CommsLogger logger) {
        loggers.remove(logger);
    }

    @Override
    public CrashLogSection onCrash() {
        Log.d(TAG, "shutting down commo due to crash");
        if (commo != null)
            commo.shutdown();
        Log.d(TAG, "commo shutdown due to crash complete");
        commo = null;
        return null;
    }

    /**
     * Set the preferred endpoint for responding to calls to be streaming
     * over mesh.
     */
    public void setPreferStreamEndpoint(final boolean preferStream) {
        if (commo != null && (commo instanceof DefaultCommsProvider))
            ((DefaultCommsProvider) commo)
                    .setPreferStreamEndpoint(preferStream);
    }

    /**
     * Used by plugins to trigger a rescan ahead of the wait period.
     */
    public void triggerIfaceRescan() {
        scannerThread.interrupt();
    }

    @Override
    public void onSharedPreferenceChanged(final SharedPreferences prefs,
            final String key) {

        if (key == null)
            return;

        if (key.equals("insertDestInDirectedCoT")) {
            if (commo instanceof DefaultCommsProvider)
                ((DefaultCommsProvider) commo).setDestUidInsertionEnabled(
                        prefs.getBoolean(key, false));
        } else if (key.equals("ppp0_highspeed_capable")) {
            setPPPIncluded(prefs.getBoolean(key, false));
        } else if (key.equals("httpClientPermissiveMode")) {
            HttpUtil.setGlobalHttpPermissiveMode(prefs.getBoolean(key, false));
        } else if (key.equals("monitorServerConnections")) {
            if (commo instanceof DefaultCommsProvider)
                ((DefaultCommsProvider) commo)
                        .setStreamMonitorEnabled(prefs.getBoolean(key, true));
        } else if (FileSystemUtils.isEquals(key,
                "autoDisableMeshSAWhenStreaming")) {
            setPreferStreamEndpoint(
                    prefs.getBoolean("autoDisableMeshSAWhenStreaming", false));
        } else if (key.equals("network_multicast_loopback")) {
            if (commo instanceof DefaultCommsProvider)
                ((DefaultCommsProvider) commo).setMulticastLoopbackEnabled(
                        prefs.getBoolean(key, false));
        } else if (key.equals("network_quic_enabled")) {
            setQuicEnabled(prefs.getBoolean(key, false));
        } else if (key.equals("exclusiveConnection")) {
            reconnectStreams();
        }
        try {
            switch (key) {
                case MissionPackagePreferenceListener.fileshareDownloadAttempts:
                    commo.setMissionPackageNumTries(getInt(prefs,
                            MissionPackagePreferenceListener.fileshareDownloadAttempts,
                            MissionPackageDownloader.DEFAULT_DOWNLOAD_RETRIES));
                    break;
                case MissionPackagePreferenceListener.filesharingConnectionTimeoutSecs:
                    commo.setMissionPackageConnTimeout(getInt(prefs,
                            MissionPackagePreferenceListener.filesharingConnectionTimeoutSecs,
                            MissionPackageReceiver.DEFAULT_CONNECTION_TIMEOUT_SECS));
                    break;
                case MissionPackagePreferenceListener.filesharingTransferTimeoutSecs:
                    commo.setMissionPackageTransferTimeout(getInt(prefs,
                            MissionPackagePreferenceListener.filesharingTransferTimeoutSecs,
                            MissionPackageReceiver.DEFAULT_TRANSFER_TIMEOUT_SECS));
                    break;
                case CotMapComponent.PREF_API_SECURE_PORT:
                    commo.setMissionPackageHttpsPort(SslNetCotPort
                            .getServerApiPort(SslNetCotPort.Type.SECURE));
                    break;
                case CotMapComponent.PREF_API_UNSECURE_PORT:
                    commo.setMissionPackageHttpPort(SslNetCotPort
                            .getServerApiPort(SslNetCotPort.Type.UNSECURE));
                    break;
                case "networkMeshKey":
                    String meshKey = prefs.getString(key, null);
                    if (meshKey != null) {
                        if (meshKey.length() != 64) {
                            meshKey = "";
                        }
                        AtakAuthenticationDatabase.saveCredentials(
                                AtakAuthenticationCredentials.TYPE_APK_DOWNLOADER,
                                "com.atakmap.app.meshkey", "atakuser", meshKey,
                                false);
                        rekey();
                        prefs.edit().remove(key).apply();
                    }
                    break;
            }
        } catch (CommoException e) {
            Log.e(TAG, "Error setting Mission package related settings");
        }
    }

    private void rekey() {
        AtakAuthenticationCredentials credentials = AtakAuthenticationDatabase
                .getCredentials(
                        AtakAuthenticationCredentials.TYPE_APK_DOWNLOADER,
                        "com.atakmap.app.meshkey");
        if (credentials != null
                && !FileSystemUtils.isEmpty(credentials.password)
                && credentials.password.length() == 64) {
            byte[] akey = credentials.password.substring(0, 32)
                    .getBytes(FileSystemUtils.UTF8_CHARSET);
            byte[] ckey = credentials.password.substring(32, 64)
                    .getBytes(FileSystemUtils.UTF8_CHARSET);
            try {
                //Log.d(TAG, "setting key");
                commo.setCryptoKeys(akey, ckey);
            } catch (CommoException ce) {
                Log.e(TAG, "error during setkey with key");
            }
        } else {
            try {
                //Log.d(TAG, "unsetting key");
                commo.setCryptoKeys(null, null);
            } catch (CommoException ce) {
                Log.e(TAG, "error during setkey without key");
            }
        }
    }

    private int getInt(SharedPreferences prefs, String key, int defVal) {
        try {
            return prefs.getInt(key, defVal);
        } catch (Exception e) {
            try {
                return Integer.parseInt(prefs.getString(key,
                        String.valueOf(defVal)));
            } catch (Exception ignore) {
            }
        }
        return defVal;
    }

    public static CommsMapComponent getInstance() {
        return _instance;
    }

    public CotService getCotService() {
        return cotService;
    }

    private final HashSet<CotServiceRemote.InputsChangedListener> inputsChangedListeners;
    private final HashSet<CotServiceRemote.OutputsChangedListener> outputsChangedListeners;

    public void addInputsChangedListener(
            CotServiceRemote.InputsChangedListener listener) {
        synchronized (inputsChangedListeners) {
            inputsChangedListeners.add(listener);
        }
    }

    public void removeInputsChangedListener(
            CotServiceRemote.InputsChangedListener listener) {
        synchronized (inputsChangedListeners) {
            inputsChangedListeners.remove(listener);
        }
    }

    public void addOutputsChangedListener(
            CotServiceRemote.OutputsChangedListener listener) {
        synchronized (outputsChangedListeners) {
            outputsChangedListeners.add(listener);
        }
    }

    public void removeOutputsChangedListener(
            CotServiceRemote.OutputsChangedListener listener) {
        synchronized (outputsChangedListeners) {
            outputsChangedListeners.remove(listener);
        }
    }

    private void fireOutputUpdated(final TAKServer port) {
        HashSet<CotServiceRemote.OutputsChangedListener> fire;
        synchronized (outputsChangedListeners) {
            fire = new HashSet<>(
                    outputsChangedListeners);
        }

        final HashSet<CotServiceRemote.OutputsChangedListener> fireFinal = fire;
        mapView.post(new Runnable() {
            @Override
            public void run() {
                for (CotServiceRemote.OutputsChangedListener listener : fireFinal) {
                    listener.onCotOutputUpdated(port.getData());
                }
            }
        });
    }

    private void fireOutputRemoved(final TAKServer port) {
        HashSet<CotServiceRemote.OutputsChangedListener> fire;
        synchronized (outputsChangedListeners) {
            fire = new HashSet<>(
                    outputsChangedListeners);
        }

        final HashSet<CotServiceRemote.OutputsChangedListener> fireFinal = fire;
        if (mapView != null)
            mapView.post(new Runnable() {
                @Override
                public void run() {
                    for (CotServiceRemote.OutputsChangedListener listener : fireFinal) {
                        listener.onCotOutputRemoved(port.getData());
                    }
                }
            });
    }

    private void fireInputAdded(final TAKServer port) {
        HashSet<CotServiceRemote.InputsChangedListener> fire;
        synchronized (inputsChangedListeners) {
            fire = new HashSet<>(
                    inputsChangedListeners);
        }

        final HashSet<CotServiceRemote.InputsChangedListener> fireFinal = fire;
        if (mapView != null)
            mapView.post(new Runnable() {
                @Override
                public void run() {
                    for (CotServiceRemote.InputsChangedListener listener : fireFinal) {
                        listener.onCotInputAdded(port.getData());
                    }
                }
            });
    }

    private void fireInputRemoved(final TAKServer port) {
        HashSet<CotServiceRemote.InputsChangedListener> fire;
        synchronized (inputsChangedListeners) {
            fire = new HashSet<>(
                    inputsChangedListeners);
        }

        final HashSet<CotServiceRemote.InputsChangedListener> fireFinal = fire;
        mapView.post(new Runnable() {
            @Override
            public void run() {
                for (CotServiceRemote.InputsChangedListener listener : fireFinal) {
                    listener.onCotInputRemoved(port.getData());
                }
            }
        });
    }

    /**
     * Retrieve a listing of all the networks inputs/outputs/streaming connections
     * as an uber bundle.  The returned bundle is going to contain a parcelableArray of 
     * streams, outputs, and inputs.   
     * <pre>
     *  Bundle b = getAllPortsBundle();
     *  Bundle[] streams = b.getParcelableArray("streams", null);
     *  Bundle[] outputs = b.getParcelableArray("outputs", null);
     *  Bundle[] inputs = b.getParcelableArray("inputs", null);
     *  
     * where an output bundle contains the following
     *    TAKServer.DESCRIPTION_KEY
     *    TAKServer.ENABLED_KEY
     *    TAKServer.CONNECTED_KEY
     *    TAKServer.CONNECT_STRING_KEY
     *  </pre>
     */
    public Bundle getAllPortsBundle() {
        Bundle[] outputs, inputs, streams;
        int i;

        synchronized (outputPorts) {
            outputs = new Bundle[outputPorts.size()];
            i = 0;
            for (OutputPortInfo p : outputPorts.values())
                outputs[i++] = p.outputPort.getData();
        }
        synchronized (inputPorts) {
            inputs = new Bundle[inputPorts.size()];
            i = 0;
            for (InputPortInfo p : inputPorts.values())
                inputs[i++] = p.inputPort.getData();
        }
        synchronized (streamPorts) {
            streams = new Bundle[streamPorts.size()];
            i = 0;
            for (TAKServer p : streamPorts.values())
                streams[i++] = p.getData();
        }

        Bundle full = new Bundle();
        full.putParcelableArray("streams", streams);
        full.putParcelableArray("outputs", outputs);
        full.putParcelableArray("inputs", inputs);
        return full;

    }

    void setNonStreamsEnabled(boolean en) {
        if (en != nonStreamsEnabled) {
            nonStreamsEnabled = en;

            recreateAllIns();
            recreateAllOuts();
            mpio.reconfigLocalWebServer(httpsCertFile);
        }
    }

    private void recreateAllOuts() {
        Map<String, OutputPortInfo> localOuts;
        synchronized (outputPorts) {
            localOuts = new HashMap<>(outputPorts);
        }
        for (Map.Entry<String, OutputPortInfo> e : localOuts.entrySet()) {
            addOutput(e.getKey(), e.getValue().outputPort.getData(),
                    e.getValue().netPort, e.getValue().mcast);
        }
    }

    private void recreateAllIns() {
        Map<String, InputPortInfo> localIns;
        synchronized (inputPorts) {
            localIns = new HashMap<>(inputPorts);
        }
        for (Map.Entry<String, InputPortInfo> e : localIns.entrySet()) {
            if (e.getValue().isTcp)
                addTcpInput(e.getKey(), e.getValue().inputPort.getData(),
                        e.getValue().netPort);
            else
                addInput(e.getKey(), e.getValue().inputPort.getData(),
                        e.getValue().netPort, e.getValue().mcast);
        }
    }

    /**
     * Registers an additional CoT event listener to be used if the CoT Event is completely unknown by the
     * system.   Note that a base type of the CoTEvent might be supported by the system and not the
     * subtype.   In this case the system will still process the CoTEvent and the supplied CoTEventListener
     * will not be called.
     * @param cel the cot event listener
     */
    public void addOnCotEventListener(CotServiceRemote.CotEventListener cel) {
        cotEventListeners.add(cel);
    }

    /**
     * Unregisters the previously registered additional CoT event listener.
     * @param cel the cot event listener
     */
    public void removeOnCotEventListener(
            CotServiceRemote.CotEventListener cel) {
        cotEventListeners.remove(cel);
    }

    @Override
    public void onDestroyImpl(Context context, MapView view) {
        AtakPreferences prefs = AtakPreferences
                .getInstance(mapView.getContext());
        prefs.unregisterListener(this);

        if (connectivityManager != null) {
            connectivityManager
                    .unregisterNetworkCallback(connectivityManagerCallback);
            connectivityManager = null;
        }

        rescan = false;
        if (multicastLock != null)
            multicastLock.release();

        if (wifiLock != null)
            wifiLock.release();

        if (_credListener != null) {
            this.unregisterReceiver(context, _credListener);
            _credListener = null;
        }

        if (_componentsListener != null) {
            unregisterReceiver(context, _componentsListener);
            _componentsListener = null;
        }

        AtakBroadcast.getInstance()
                .unregisterSystemReceiver(networkManagerLite);

        // dispose of the registered loggers
        for (CommsLogger logger : loggers) {
            try {
                logger.dispose();
            } catch (Exception e) {
                Log.e(TAG, "error disposing of a logger", e);
            }
        }
        loggers.clear();

        if (this.takServerListener != null)
            this.takServerListener.dispose();

        vpnMonitor.cancel();
    }

    @Override
    public void onStart(Context context, MapView view) {
        reacquireLock();
    }

    @Override
    public void onStop(Context context, MapView view) {
        reacquireLock();
    }

    @Override
    public void onPause(Context context, MapView view) {
        reacquireLock();
    }

    private void reacquireLock() {
        if (!multicastLock.isHeld()) {
            Log.d(TAG, "re-acquiring wifi multicast lock");
            multicastLock = wifi
                    .createMulticastLock("mulicastLock in CotService onReset");
            multicastLock.acquire();
        }
    }

    @Override
    public void onResume(Context context, MapView view) {
        reacquireLock();
    }

    /**
     * Used internally by ATAK to set up the direct processing workflow for 
     * CotEvent messages.   Do not use for external development application.
     * Any external calls to this method will silently fail.
     *
     * @param dp implementation of the DirectCotProcessor which is CotMapAdapter only.
     */
    public synchronized void registerDirectProcessor(
            final DirectCotProcessor dp) {
        if (directProcessor == null)
            directProcessor = dp;
    }

    /**
     * Allows a enternal plugin to provide a capability to have one last look at a CotEvent 
     * and uid list used.
     * @param psp the Presend Processor triggered right before the message is sent down into the
     *            sending libraries.
     */
    public void registerPreSendProcessor(final PreSendProcessor psp) {
        preSendProcessor = psp;
    }

    /**
     * Enabled PPP as a tunnel for use with the trellisware wireless connector.   When called, it
     * will trigger a rescan of the interfaces with ppp[0..4] included or excluded.
     * @param included true if ppp[0..4] should be enabled or false if it should be disabled.
     */
    public void setPPPIncluded(final boolean included) {
        if (included) {
            Log.d(TAG,
                    "user has indicated that Point-to-Point (ppp) links are high speed capable");
        } else {
            Log.d(TAG,
                    "user has indicated that Point-to-Point (ppp) links are not high speed capable");
        }
        pppIncluded = included;
        rescanInterfaces();
    }

    private void rescanInterfaces() {
        Set<String> in = scanInterfaces(false);
        Set<String> out = scanInterfaces(false);
        in.removeAll(hwAddressesIn);
        out.removeAll(hwAddressesOut);

        if (!in.isEmpty()) {
            Log.i(TAG,
                    "Set of input network interfaces changed - rebuilding inputs!");
            in = scanInterfaces(true);
            synchronized (hwAddressesIn) {
                hwAddressesIn.clear();
                hwAddressesIn.addAll(in);
            }
            recreateAllIns();
        }
        if (!out.isEmpty()) {
            Log.i(TAG,
                    "Set of output network interfaces changed - rebuilding outputs!");
            out = scanInterfaces(true);
            synchronized (hwAddressesOut) {
                hwAddressesOut.clear();
                hwAddressesOut.addAll(out);
            }
            recreateAllOuts();
        }
    }

    private Set<String> scanInterfaces(boolean doLog) {
        final Set<String> ret = new HashSet<>();

        try {
            Set<String> disabledSet = new HashSet<>();
            List<NetworkManagerLite.NetworkDevice> managed = NetworkManagerLite
                    .getNetworkDevices();
            for (NetworkManagerLite.NetworkDevice device : managed) {
                if (device.getDisabled()) {
                    final String name = device.getInterfaceName();
                    if (name != null)
                        disabledSet.add(name);
                }
            }

            Enumeration<NetworkInterface> enifs = NetworkInterface
                    .getNetworkInterfaces();
            if (enifs != null) {
                List<NetworkInterface> nifs = Collections.list(enifs);
                for (NetworkInterface nif : nifs) {
                    // nwradioX and usbX are both interface names assigned to the HHL16 radio
                    // nwradioX is a renamed variant of usbX found on NettWarrior devices.
                    // the MPU5 uses waverelay as the network device name
                    final String name = nif.getName();
                    if (name != null
                            && (name.startsWith("wlan")
                                    || name.startsWith("tun")
                                    || name.startsWith("waverelay")
                                    || name.startsWith("rndis")
                                    || name.startsWith("eth")
                                    || name.startsWith("nwradio")
                                    || name.startsWith("usb")
                                    || (pppIncluded
                                            && name.startsWith("ppp")))) {
                        if (!disabledSet.contains(name)) {
                            if (doLog)
                                Log.d(TAG,
                                        "network device registering: " + name);
                            ret.add(name);
                        } else {
                            if (doLog)
                                Log.d(TAG, "network device disabled, skipping: "
                                        + name);
                        }
                    } else {
                        if (doLog)
                            Log.d(TAG, "network device unsupported, skipping: "
                                    + name);
                    }
                }
            }
        } catch (NullPointerException | SocketException e) {
            // ^ NPE observed on some less-prevalent devices coming
            // out of internal impl of android
            // NetworkInterface.getNetworkInterfaces(). See ATAK-15755
            if (doLog)
                Log.d(TAG,
                        "exception occurred trying to build the interface list",
                        e);
        }
        return ret;
    }

    private List<String> getInterfaceNames(boolean input) {
        ArrayList<String> ret;
        Set<String> addrSet = input ? hwAddressesIn : hwAddressesOut;
        //noinspection SynchronizationOnLocalVariableOrMethodParameter
        synchronized (addrSet) {
            ret = new ArrayList<>(addrSet);
        }
        return ret;
    }

    void setTTL(int ttl) {
        if (commo instanceof DefaultCommsProvider)
            ((DefaultCommsProvider) commo).setTTL(ttl);
    }

    /**
     * Value in full seconds till the socket it torn down and recreated.
     */
    void setUdpNoDataTimeout(int seconds) {
        if (commo instanceof DefaultCommsProvider)
            ((DefaultCommsProvider) commo).setUdpNoDataTimeout(seconds);
    }

    void setTcpConnTimeout(int millseconds) {
        if (commo instanceof DefaultCommsProvider)
            ((DefaultCommsProvider) commo)
                    .setTcpConnTimeout(millseconds / 1000);
    }

    private QuicInboundNetInterface quicIface = null;

    void setQuicEnabled(boolean en) {
        if (!(commo instanceof DefaultCommsProvider)) {
            Log.w(TAG, "Quic not supported by Comms Provider");
            return;
        }
        if (en && quicIface == null) {
            try {
                byte[] cert = mpio.getHttpsServerCert();
                quicIface = ((DefaultCommsProvider) commo)
                        .addQuicInboundInterface(4243, cert,
                                mpio.getHttpsCertStoreSecret());

            } catch (CommoException ex) {
                Log.e(TAG, "Could not enable quic", ex);
            }
        } else if (!en && quicIface != null) {
            ((DefaultCommsProvider) commo)
                    .removeQuicInboundInterface(quicIface);
            quicIface = null;
        }
    }

    // Assumes holding of netIfaces lock
    private List<PhysicalNetInterface> addInputsForMcastSet(
            Set<String> mcastSet, int port, List<String> ifaceNames) {

        boolean inputSupported = commo.hasFeature(
                CommsProvider.CommsFeature.MESH_MODE_INPUT_SUPPORTED)
                || commo.hasFeature(
                        CommsProvider.CommsFeature.ENDPOINT_SUPPORT);
        // remove null unicast-only entry, return null if that null is only one
        Set<String> nonNullSet = new HashSet<>();
        for (String s : mcastSet) {
            if (s != null)
                nonNullSet.add(s);
        }
        String[] mcastAddresses = null;
        if (!nonNullSet.isEmpty()) {
            mcastAddresses = mcastSet.toArray(new String[0]);
        }

        List<PhysicalNetInterface> netIfaces = new ArrayList<>();
        for (String name : ifaceNames) {
            PhysicalNetInterface iface;
            try {
                if (inputSupported) {
                    iface = commo.addInboundInterface(
                            name, port, mcastAddresses,
                            false);
                    netIfaces.add(iface);
                }
            } catch (CommoException e) {
                Log.d(TAG, "Could not create input for " + name + " mcast: "
                        + Arrays.toString(mcastAddresses) + ":" + port, e);
            }
        }
        return netIfaces;
    }

    public void addInput(String uniqueKey, Bundle portBundle, int port,
            String mcastAddress) {
        TAKServer cotport = new TAKServer(portBundle);
        boolean isNowEnabled = cotport.isEnabled() && nonStreamsEnabled;

        if (commo == null || !(commo
                .hasFeature(CommsProvider.CommsFeature.ENDPOINT_SUPPORT)
                || commo.hasFeature(
                        CommsProvider.CommsFeature.MESH_MODE_INPUT_SUPPORTED)))
            return;

        synchronized (inputIfaces) {
            List<String> ifaceNames = getInterfaceNames(true);

            // Remove interfaces associated with this input's network port
            if (inputIfaces.containsKey(port)) {
                // need to remove it to change parameters/disable
                List<PhysicalNetInterface> ifaces = inputIfaces.remove(port);
                if (ifaces != null)
                    for (PhysicalNetInterface iface : ifaces) {
                        try {
                            commo
                                    .removeInboundInterface(iface);
                        } catch (IllegalArgumentException iae) {
                            Log.e(TAG,
                                    "unable to remove inbound interface for: "
                                            + iface);
                        }
                    }
            }

            // If this Input existed before....
            InputPortInfo portInfo;
            synchronized (inputPorts) {
                portInfo = inputPorts.remove(uniqueKey);
            }

            if (portInfo != null && !portInfo.isTcp) {
                // Remove any existing interfaces associated with the old port
                if (inputIfaces.containsKey(portInfo.netPort)) {
                    List<PhysicalNetInterface> ifaces = inputIfaces
                            .remove(portInfo.netPort);
                    if (ifaces != null) {
                        for (PhysicalNetInterface iface : ifaces) {
                            try {
                                commo
                                        .removeInboundInterface(iface);
                            } catch (IllegalArgumentException iae) {
                                Log.e(TAG,
                                        "unable to remove inbound interface for: "
                                                + iface);
                            }
                        }
                    }
                }

                // Now adjust the old port's mcast set and re-add the ifaces if needed
                Set<String> mcast = mcastAddrsByPort.get(portInfo.netPort);
                if (mcast != null) {
                    mcast.remove(portInfo.mcast);
                    if (mcast.isEmpty()) {
                        mcastAddrsByPort.remove(portInfo.netPort);
                    } else if (portInfo.netPort != port) {
                        // Add old port back with remaining enabled mcasts
                        List<PhysicalNetInterface> netIfaces = addInputsForMcastSet(
                                mcast, portInfo.netPort, ifaceNames);
                        inputIfaces.put(portInfo.netPort, netIfaces);
                    }
                }
            }

            Set<String> mcastSet = mcastAddrsByPort.get(port);
            if (isNowEnabled) {
                // Add to mcast list of new port
                if (mcastSet == null) {
                    mcastSet = new HashSet<>();
                    mcastAddrsByPort.put(port, mcastSet);
                }
                mcastSet.add(mcastAddress);
            }

            // If there are any addresses left enabled for our net port, add the ifaces with that set of addrs
            if (mcastSet != null && !mcastSet.isEmpty()) {
                List<PhysicalNetInterface> netIfaces = addInputsForMcastSet(
                        mcastSet, port, ifaceNames);
                inputIfaces.put(port, netIfaces);
            }
        }
        InputPortInfo ipi;
        synchronized (inputPorts) {
            inputPorts.put(uniqueKey, ipi = new InputPortInfo(
                    new TAKServer(portBundle), port, mcastAddress));
        }
        fireInputAdded(ipi.inputPort);
    }

    public void addTcpInput(String uniqueKey, Bundle portBundle, int port) {
        TAKServer cotport = new TAKServer(portBundle);
        boolean isNowEnabled = cotport.isEnabled() && nonStreamsEnabled;
        boolean inputSupported = commo.hasFeature(
                CommsProvider.CommsFeature.MESH_MODE_INPUT_SUPPORTED)
                || commo.hasFeature(
                        CommsProvider.CommsFeature.ENDPOINT_SUPPORT);

        synchronized (tcpInputIfaces) {
            if (tcpInputIfaces.containsKey(uniqueKey)) {
                // need to remove it to change parameters/disable
                TcpInboundNetInterface iface = tcpInputIfaces.remove(uniqueKey);
                if (iface != null && inputSupported)
                    commo
                            .removeTcpInboundInterface(iface);
            }

            if (isNowEnabled && inputSupported) {
                try {
                    TcpInboundNetInterface iface = commo
                            .addTcpInboundInterface(port);
                    tcpInputIfaces.put(uniqueKey, iface);
                } catch (CommoException e) {
                    // do not do anything but log the error and do not add the tcp port
                    Log.d(TAG, "Could not create iface " + uniqueKey + " for: "
                            + cotport, e);
                    return;
                }
            }
        }
        InputPortInfo ipi;
        synchronized (inputPorts) {
            inputPorts.put(uniqueKey, ipi = new InputPortInfo(
                    new TAKServer(portBundle), port));
        }
        fireInputAdded(ipi.inputPort);
    }

    public void removeInput(String uniqueKey) {
        // If this Input existed before....
        InputPortInfo portInfo;
        synchronized (inputPorts) {
            portInfo = inputPorts.remove(uniqueKey);
        }

        if (commo == null)
            return;

        boolean inputSupported = commo.hasFeature(
                CommsProvider.CommsFeature.MESH_MODE_INPUT_SUPPORTED)
                || commo.hasFeature(
                        CommsProvider.CommsFeature.ENDPOINT_SUPPORT);
        synchronized (inputIfaces) {
            if (portInfo != null && !portInfo.isTcp) {
                // Remove any existing interfaces associated with the old port
                if (inputIfaces.containsKey(portInfo.netPort)) {
                    List<PhysicalNetInterface> ifaces = inputIfaces
                            .remove(portInfo.netPort);
                    if (ifaces != null
                            && inputSupported) {
                        for (PhysicalNetInterface iface : ifaces)
                            commo
                                    .removeInboundInterface(iface);
                    }
                }

                // Now adjust the old port's mcast set and re-add the ifaces if needed
                Set<String> mcast = mcastAddrsByPort.get(portInfo.netPort);
                if (mcast != null) {
                    mcast.remove(portInfo.mcast);
                    if (mcast.isEmpty()) {
                        mcastAddrsByPort.remove(portInfo.netPort);
                    } else {
                        List<String> ifaceNames = getInterfaceNames(true);
                        List<PhysicalNetInterface> netIfaces = addInputsForMcastSet(
                                mcast, portInfo.netPort, ifaceNames);
                        inputIfaces.put(portInfo.netPort, netIfaces);
                    }
                }
            }
        }
        synchronized (tcpInputIfaces) {
            TcpInboundNetInterface iface = tcpInputIfaces.remove(uniqueKey);
            if (iface != null && inputSupported)
                commo.removeTcpInboundInterface(iface);
        }
        if (portInfo != null)
            fireInputRemoved(portInfo.inputPort);
    }

    public void addOutput(String uniqueKey, Bundle portBundle,
            int port, String mcastAddress) {
        TAKServer cotport = new TAKServer(portBundle);
        boolean isNowEnabled = cotport.isEnabled() && nonStreamsEnabled;
        boolean isForChat = cotport.isChat();
        boolean isOutputEnabled = commo
                .hasFeature(CommsProvider.CommsFeature.ENDPOINT_SUPPORT)
                || commo.hasFeature(
                        CommsProvider.CommsFeature.MESH_MODE_OUTPUT_SUPPORTED);

        synchronized (broadcastIfaces) {
            if (broadcastIfaces.containsKey(uniqueKey)) {
                // remove the ifaces from commo but leave them in our list of ports
                List<PhysicalNetInterface> netIfaces = broadcastIfaces
                        .remove(uniqueKey);
                if (netIfaces != null
                        && isOutputEnabled)
                    for (PhysicalNetInterface iface : netIfaces)
                        commo
                                .removeBroadcastInterface(iface);
            }

            if (isNowEnabled && isOutputEnabled) {
                List<PhysicalNetInterface> netIfaces = new ArrayList<>();
                CoTMessageType[] types = new CoTMessageType[] {
                        isForChat ? CoTMessageType.CHAT
                                : CoTMessageType.SITUATIONAL_AWARENESS
                };

                boolean isReallyMcast = true;
                try {
                    if (!NetworkUtils.isMulticastAddress(mcastAddress))
                        isReallyMcast = false;
                } catch (Exception ignored) {
                }

                if (!isReallyMcast) {
                    PhysicalNetInterface iface;
                    try {
                        iface = commo
                                .addBroadcastInterface(types,
                                        mcastAddress, port);
                        netIfaces.add(iface);
                    } catch (CommoException e) {
                        Log.d(TAG, "Could not create output iface " + uniqueKey
                                + " for mcast: " + mcastAddress
                                + ":" + port, e);
                    }

                } else {
                    List<String> ifaceNames = getInterfaceNames(false);
                    for (String name : ifaceNames) {
                        PhysicalNetInterface iface;
                        try {
                            iface = commo
                                    .addBroadcastInterface(name, types,
                                            mcastAddress, port);
                            netIfaces.add(iface);
                        } catch (CommoException e) {

                            Log.d(TAG, "Could not create output iface "
                                    + uniqueKey
                                    + " for " + name + " mcast: "
                                    + mcastAddress
                                    + ":" + port, e);
                        }
                    }
                }
                broadcastIfaces.put(uniqueKey, netIfaces);
            }
        }
        synchronized (outputPorts) {
            OutputPortInfo out = new OutputPortInfo(cotport, port,
                    mcastAddress);
            outputPorts.put(uniqueKey, out);
        }
        fireOutputUpdated(cotport);
    }

    public void removeOutput(String uniqueKey) {
        synchronized (broadcastIfaces) {
            List<PhysicalNetInterface> ifaces = broadcastIfaces
                    .remove(uniqueKey);
            if (ifaces != null && (commo
                    .hasFeature(CommsProvider.CommsFeature.ENDPOINT_SUPPORT)
                    || commo.hasFeature(
                            CommsProvider.CommsFeature.MESH_MODE_OUTPUT_SUPPORTED))) {
                for (PhysicalNetInterface iface : ifaces)
                    commo
                            .removeBroadcastInterface(iface);
            } // else this was disabled
        }
        OutputPortInfo cotport;
        synchronized (outputPorts) {
            cotport = outputPorts.remove(uniqueKey);
        }
        if (cotport != null)
            fireOutputRemoved(cotport.outputPort);
    }

    // null for clientCert, caCert, and certPassword must be given for SSL based protocols.
    // null for authUser and authPass to not use auth; specify both to use auth - ignored for TCP
    // protocol.
    // Set missingParams = true to forgo attempting to connect as caller knows it won't meet
    // the desired result due to one or more missing/misconfigured parameters (certs, cert passwords,
    // user, password, etc)
    public void addStreaming(String uniqueKey, Bundle portBundle,
            boolean missingParams,
            CotServiceRemote.Proto proto, String hostname, int port,
            byte[] clientCert, byte[] caCert,
            String certPassword, String caCertPassword, String authUser,
            String authPass) {
        TAKServer cotport = new TAKServer(portBundle);
        boolean isNowEnabled = cotport.isEnabled();
        String streamId = null;
        String oldStreamId = null;
        String errReason = null;

        Integer notifyId;
        synchronized (streamPorts) {
            notifyId = streamNotificationIds.get(uniqueKey);
            if (notifyId == null) {
                notifyId = NotificationUtil.getInstance().reserveNotifyId();
                streamNotificationIds.put(uniqueKey, notifyId);
            }
        }
        NotificationUtil.getInstance().clearNotification(notifyId);

        synchronized (streamingIfaces) {
            // Also mark it disconnected
            cotport.setConnected(false);
            if (streamingIfaces.containsKey(uniqueKey)) {
                // Need to remove it to "disable" or change params
                StreamingNetInterface iface = streamingIfaces.remove(uniqueKey);
                if (iface != null && (commo instanceof DefaultCommsProvider)) {
                    oldStreamId = iface.streamId;
                    ((DefaultCommsProvider) commo)
                            .removeStreamingInterface(iface);
                }
            }

            if (isNowEnabled && !missingParams
                    && (commo instanceof DefaultCommsProvider)) {
                StreamingNetInterface netIface;
                CoTMessageType[] types = new CoTMessageType[] {
                        CoTMessageType.CHAT,
                        CoTMessageType.SITUATIONAL_AWARENESS
                };
                StreamingTransport transport = null;
                switch (proto) {
                    case ssl:
                        transport = StreamingTransport.SSL;
                        break;
                    case tcp:
                        transport = StreamingTransport.TCP;
                        break;
                    case quic:
                        transport = StreamingTransport.QUIC;
                        break;
                    default:
                        throw new IllegalArgumentException(
                                "Unsupported protocol");
                }
                try {
                    netIface = ((DefaultCommsProvider) commo)
                            .addStreamingInterface(transport,
                                    hostname, port,
                                    types, clientCert, caCert, certPassword,
                                    caCertPassword, authUser, authPass);
                    streamingIfaces.put(uniqueKey, netIface);
                    streamId = netIface.streamId;
                } catch (CommoException e) {
                    errReason = e.getMessage();
                    Log.d(TAG, "Could not create streaming iface " + uniqueKey
                            + " for host: " + hostname + ":"
                            + port + " types: " + Arrays.toString(types), e);
                }
            }
        }

        synchronized (streamPorts) {
            streamPorts.put(uniqueKey, cotport);
            if (oldStreamId != null)
                streamKeys.remove(oldStreamId);

            if (streamId != null)
                streamKeys.put(streamId, uniqueKey);
        }

        fireOutputUpdated(cotport);

        if (errReason != null) {
            NotificationUtil.getInstance().postNotification(
                    notifyId,
                    NotificationUtil.GeneralIcon.NETWORK_ERROR.getID(),
                    NotificationUtil.RED,
                    mapView.getContext().getString(
                            R.string.connection_config_error),
                    mapView.getContext().getString(R.string.unable_to_config)
                            + cotport.getDescription() + " ("
                            + errReason + ")",
                    null,
                    true);
        }
    }

    public TAKServer removeStreaming(String uniqueKey) {
        String id = null;
        synchronized (streamingIfaces) {
            StreamingNetInterface iface = streamingIfaces.remove(uniqueKey);
            if (iface != null && (commo instanceof DefaultCommsProvider)) {
                id = iface.streamId;
                ((DefaultCommsProvider) commo).removeStreamingInterface(iface);
            } // else it just wasn't enabled or didn't exist....

        }
        TAKServer cotport;
        synchronized (streamPorts) {
            cotport = streamPorts.remove(uniqueKey);
            streamKeys.remove(id);
            Integer notifierId = streamNotificationIds.remove(uniqueKey);
            if (notifierId != null)
                NotificationUtil.getInstance().clearNotification(
                        notifierId);
        }
        if (cotport != null)
            fireOutputRemoved(cotport);
        return cotport;
    }

    // DEPRECATED
    // Supports only tcp endpoints for backwards compatibility
    public void sendCoTToEndpoint(CotEvent e, String endpoint) {

        if (!(commo instanceof DefaultCommsProvider)) {
            Log.d(TAG, "CommsProvider doesn't support endpoint connections",
                    new Exception());
            return;
        }

        if (endpoint == null) {
            Log.d(TAG, "no endpoint supplied", new Exception());
            return;
        }

        String[] s = endpoint.split(":");
        if (s.length != 3) {
            Log.d(TAG, "Unsupported endpoint string: " + endpoint);
            return;
        }
        if (!s[2].toLowerCase(LocaleUtil.getCurrent()).equals("tcp")) {
            Log.d(TAG, "Unsupported endpoint protocol: " + s[2]);
            return;
        }
        int port;
        try {
            port = Integer.parseInt(s[1]);
        } catch (NumberFormatException ex) {
            Log.d(TAG, "Invalid endpoint string " + endpoint
                    + " - port number invalid");
            return;
        }

        if (e == null) {
            Log.e(TAG,
                    "Empty CotEvent received while trying to send (ignore).");
            return;
        }

        try {
            final String event = e.toString();
            ((DefaultCommsProvider) commo).sendCoTTcpDirect(s[0], port, event);

            for (CommsLogger logger : loggers) {
                try {
                    logger.logSend(e, endpoint);
                } catch (Exception err) {
                    Log.e(TAG, "error occurred with a logger", err);
                }
            }

        } catch (CommoException ex) {
            Log.e(TAG,
                    "Invalid cot message or destination for tcp direct send to "
                            + endpoint + " msg = " + e);
        }
    }

    /**
     *
     * if failedContacts is non-null, fill it with those contacts who are not known via the specified method
     * or who are invalid or missing on the network
     *
     * @param failedContactUids a list that will be filled with the list of sending contacts that failed.
     * @param e event to send
     * @param toUIDs  Destination UIDs, null for broadcast
     * @param method    method for sending
     */
    void sendCoT(
            List<String> failedContactUids,
            CotEvent e,
            String[] toUIDs,
            CoTSendMethod method) {
        if (failedContactUids != null)
            failedContactUids.clear();

        if (e == null) {
            Log.e(TAG,
                    "empty CotEvent received while trying to send (ignore).");
            return;
        }

        if (commo == null) {
            Log.e(TAG,
                    "commo == null, likely shutting down so skipping send....");
            return;
        }

        try {
            if (preSendProcessor != null) {
                preSendProcessor.processCotEvent(e, toUIDs);
            }
        } catch (Exception ex) {
            Log.e(TAG, "preSendProcessor failed", ex);
        }

        if (toUIDs == null) {
            try {
                final String event = e.toString();
                if (commo != null)
                    commo.broadcastCoT(event, method);

                for (CommsLogger logger : loggers) {
                    try {
                        logger.logSend(e, "broadcast");
                    } catch (Exception err) {
                        Log.e(TAG, "error occurred with a logger", err);
                    }
                }

            } catch (CommoException ex) {
                Log.e(TAG, "Invalid cot message for broadcast " + e);
            }
        } else {

            Vector<String> commoContacts = new Vector<>();
            synchronized (contactUids) {
                for (String uid : toUIDs) {
                    if (!contactUids.contains(uid)
                            && !commo.hasFeature(
                                    CommsProvider.CommsFeature.UNKNOWN_CONTACT)) {
                        Log.e(TAG, "Send to unknown contact " + uid,
                                new Exception());
                        continue;
                    }
                    commoContacts.add(uid);
                }
            }

            boolean success = false;
            try {
                final String event = e.toString();
                if (commo != null)
                    commo.sendCoT(commoContacts, event, method);
                success = true;
            } catch (CommoException ex) {
                Log.e(TAG, "Invalid cot message for unicast " + e);
            }

            if (success) {
                for (CommsLogger logger : loggers) {
                    try {
                        logger.logSend(e, toUIDs);
                    } catch (Exception err) {
                        Log.e(TAG, "error occurred with a logger", err);
                    }
                }
            }

            if (failedContactUids != null) {
                for (String commoContactUID : commoContacts) {
                    for (String uid : toUIDs) {
                        if (uid.equals(commoContactUID)) {
                            failedContactUids.add(uid);
                            break;
                        }
                    }
                }
            }
        }
    }

    /**
     * Send the specified CotEvent to all configured and connected TAK servers.
     * They will be routed to the specified mission on those servers.
     * Destination/routing information will be added during the send and should
     * not be specified in the CotEvent itself. 
     *  
     * @param uniqueIfaceKey the unique key identifier specifying a configured
     *                       streaming interface, or null to send to all active
     *                       streaming interfaces
     * @param mission the mission identifier used to route the message
     * @param e valid CotEvent to send
     */
    public void sendCoTToServersByMission(String uniqueIfaceKey,
            String mission, CotEvent e) {
        if ((!(commo instanceof DefaultCommsProvider))
                && !CommsProviderFactory.getProvider()
                        .hasFeature(CommsProvider.CommsFeature.MISSION_API))
            return;

        String id = null;
        if (uniqueIfaceKey != null && (commo instanceof DefaultCommsProvider)) {
            synchronized (streamingIfaces) {
                StreamingNetInterface iface = streamingIfaces
                        .get(uniqueIfaceKey);
                if (iface != null) {
                    id = iface.streamId;
                } else {
                    Log.e(TAG,
                            "Invalid interface key specified for sending to server: "
                                    + uniqueIfaceKey);
                    return;
                }
            }
        }

        try {
            final String event = e.toString();
            if (commo != null)
                commo.sendCoTToServerMissionDest(id, mission, event);

            for (CommsLogger logger : loggers) {
                try {
                    logger.logSend(e, "mission " + mission);
                } catch (Exception err) {
                    Log.e(TAG, "error occurred with a logger", err);
                }
            }

        } catch (CommoException ex) {
            Log.e(TAG, "Invalid cot message for send to mission");
        }
    }

    /**
     * Send the specified CotEvent to all configured and connected TAK servers.
     * They will be routed to the "server only contact", being processed only by
     * the server itself and not send to any other clients.
     * Destination/routing information will be added during the send and should
     * not be specified in the CotEvent itself. 
     *  
     * @param uniqueIfaceKey the unique key identifier specifying a configured
     *                       streaming interface, or null to send to all active
     *                       streaming interfaces
     * @param e valid CotEvent to send
     */
    public void sendCoTToServersOnly(String uniqueIfaceKey, CotEvent e) {
        if (!(commo instanceof DefaultCommsProvider))
            return;

        String id = null;
        if (uniqueIfaceKey != null) {
            synchronized (streamingIfaces) {
                StreamingNetInterface iface = streamingIfaces
                        .get(uniqueIfaceKey);
                if (iface != null) {
                    id = iface.streamId;
                } else {
                    Log.e(TAG,
                            "Invalid interface key specified for sending to server: "
                                    + uniqueIfaceKey);
                    return;
                }
            }
        }

        try {
            final String event = e.toString();
            if (commo != null && (commo instanceof DefaultCommsProvider))
                ((DefaultCommsProvider) commo).sendCoTServerControl(id, event);

            for (CommsLogger logger : loggers) {
                try {
                    logger.logSend(e, CotService.SERVER_ONLY_CONTACT);
                } catch (Exception err) {
                    Log.e(TAG, "error occurred with a logger", err);
                }
            }

        } catch (CommoException ex) {
            Log.e(TAG, "Invalid cot message for send to cot servers only");
        }
    }

    @Override
    public void sendCoTFailure(String host, int port, String errorReason) {

        NotificationUtil.getInstance().postNotification(
                31345,
                NotificationUtil.GeneralIcon.NETWORK_ERROR.getID(),
                NotificationUtil.RED,
                mapView.getContext().getString(R.string.notification_text8),
                mapView.getContext().getString(R.string.notification_text9)
                        + host + ":" + port + "/" + errorReason,
                null, true);

        Log.d(TAG, "error sending message to: " + host + " " + port + " "
                + errorReason);
    }

    /**
     * Invoked when a CoT Message has been received.  The message
     * is provided without modification. Some basic validity checking
     * is done prior to passing it off to listeners, but it is limited
     * and should not be relied upon for anything specific.
     *
     * @param message the CoT message that was received
     * @param rxEndpointId identifier of NetworkInterface upon which
     *                     the message was received, if known, or null
     *                     if not known
     */
    @Override
    public void cotMessageReceived(final String message,
            final String rxEndpointId) {

        // Check if the map components have finished loading before processing
        if (!componentsLoaded) {
            synchronized (deferredMessages) {
                // Check again inside the sync block just in case it changed
                if (!componentsLoaded) {
                    deferredMessages.add(new Pair<>(message, rxEndpointId));
                    return;
                }
            }
        }

        CotEvent cotEvent = CotEvent.parse(message);
        Bundle extras = new Bundle();
        extras.putString("from", cotEvent.getUID());
        if (rxEndpointId != null) {
            synchronized (streamPorts) {
                String appsStreamEndpoint = streamKeys.get(rxEndpointId);
                if (appsStreamEndpoint != null)
                    extras.putString("serverFrom", appsStreamEndpoint);
                else
                    extras.putString("endpointFrom", rxEndpointId);
            }
        }

        // CoTEvent is never null. Dispatch through parallel method.
        CotMapComponent.getParallelInternalDispatcher().dispatch(cotEvent,
                extras);

        for (CommsLogger logger : loggers) {
            try {

                String appsStreamEndpoint = (rxEndpointId != null)
                        ? streamKeys.get(rxEndpointId)
                        : null;
                logger.logReceive(cotEvent, rxEndpointId, appsStreamEndpoint);
            } catch (Exception err) {
                Log.e(TAG, "error occurred with a logger", err);
            }
        }

    }

    public void sendCoTInternally(final CotEvent cotEvent, Bundle extras) {
        ImportResult result = ImportResult.FAILURE;

        if (extras == null) {
            extras = new Bundle();
            extras.putString("from", "internal");
        }

        // strip out the destination message, no use for it higher up and it
        // will only be inserted during a directed send
        final CotDetail detail = cotEvent.getDetail();
        final CotDetail dest = (detail == null) ? null
                : detail.getChild("__dest");
        if (dest != null)
            cotEvent.getDetail().removeChild(dest);

        if (directProcessor != null) {
            //Log.d(TAG, "received for processing: " + cotEvent);
            result = directProcessor.processCotEvent(cotEvent,
                    extras);
        }

        if (result != ImportResult.SUCCESS) {
            //Log.d(TAG, "failed to process, redispatch received: " + cotEvent);
            // iterate over all of the CotEvent listeners and fire (cotEvent, extras);
            for (CotServiceRemote.CotEventListener cel : cotEventListeners) {
                try {
                    cel.onCotEvent(cotEvent, extras);
                } catch (Exception e) {
                    Log.e(TAG, "internal dispatching error: ", e);
                }
            }
        }
    }

    public void syncFileTransfer(CommsFileTransferListener listener,
            boolean forUpload,
            URI uri, byte[] caCert, String caCertPassword,
            String username, String password, File localFile)
            throws CommoException,
            IOException,
            InterruptedException {
        masterFileIO.doFileTransfer(listener, forUpload, uri, caCert,
                caCertPassword,
                username, password, localFile);
    }

    public void setMissionPackageReceiveInitiator(MPReceiveInitiator rxInit) {
        mpio.setRxInitiator(rxInit);
    }

    public boolean setMissionPackageEnabled(boolean enabled, int localPort,
            int secureLocalPort) {
        this.filesharingEnabled = enabled;
        if (enabled) {
            this.localWebServerPort = localPort;
            this.secureLocalWebServerPort = secureLocalPort;
        }
        if (mpio != null) {
            return mpio.reconfigFileSharing();
        }
        return false;
    }

    // file must exist until transfer ends
    public void sendMissionPackage(String[] contactUuids, File file,
            String remoteFileName, String transferName,
            MPSendListener listener)
            throws UnknownServiceException, CommoException {
        Set<String> contacts = new HashSet<>();
        synchronized (contactUids) {
            for (String uid : contactUuids) {
                if (!contactUids.contains(uid)
                        && !commo.hasFeature(
                                CommsProvider.CommsFeature.UNKNOWN_CONTACT)) {
                    Log.e(TAG, "Mission Package send to unknown contact " + uid,
                            new Exception());
                    continue;
                }
                contacts.add(uid);
            }
        }

        if (contacts.isEmpty())
            throw new UnknownServiceException(
                    "Specified contacts are not available");

        try {
            mpio.sendMissionPackage(contacts, file,
                    remoteFileName, transferName, listener);
        } catch (CommoException ex) {
            Log.e(TAG, "Failed to send mission package "
                    + file.getAbsolutePath() + "  " + ex.getMessage());
            throw ex;
        }
    }

    // Sends direct to a server identified by the server 
    // file must exist until transfer ends
    public void sendMissionPackage(String uniqueServerIfaceKey,
            File file, String transferFilename,
            MPSendListener listener)
            throws UnknownServiceException, CommoException {
        // Translate Net connect string/unique key to
        // stream id
        String streamingId;
        synchronized (streamingIfaces) {
            StreamingNetInterface iface = streamingIfaces
                    .get(uniqueServerIfaceKey);
            if (iface != null) {
                streamingId = iface.streamId;
            } else {
                Log.e(TAG,
                        "Invalid interface key specified for uploading file to server: "
                                + uniqueServerIfaceKey);
                throw new UnknownServiceException(
                        "Unknown server identifier specified");
            }
        }

        mpio.sendMissionPackage(streamingId,
                file, transferFilename,
                listener);

    }

    public com.atakmap.android.contact.Contact createKnownEndpointContact(
            String name, String ipAddr, int port) {
        IndividualContact contact = new IndividualContact(name);
        String id = contact.getUID();

        try {
            if (commo != null && (commo instanceof DefaultCommsProvider))
                ((DefaultCommsProvider) commo).configKnownEndpointContact(id,
                        name, ipAddr, port);
            return contact;
        } catch (CommoException e) {
            return null;
        }
    }

    @Override
    public void contactAdded(Contact c) {
        contactAdded(c.contactUID);
    }

    @Override
    public void contactRemoved(Contact c) {
        contactRemoved(c.contactUID);
    }

    @Override
    public void contactAdded(String contactUid) {
        synchronized (contactUids) {
            contactUids.add(contactUid);
        }
    }

    @Override
    public void contactRemoved(String contactUid) {
        synchronized (contactUids) {
            contactUids.remove(contactUid);
        }
        CotMapComponent.getInstance().stale(new String[] {
                contactUid
        });
    }

    private void interfaceChange(NetInterface iface, boolean up) {
        if (!(iface instanceof StreamingNetInterface))
            return;

        StreamingNetInterface siface = (StreamingNetInterface) iface;
        String key = siface.streamId;
        Integer notifierId;
        TAKServer port;
        synchronized (streamPorts) {
            String appsKey = streamKeys.get(key);
            if (appsKey == null)
                return;

            port = streamPorts.get(appsKey);
            if (port == null)
                return;
            port.setConnected(up);

            notifierId = streamNotificationIds.get(appsKey);
        }
        fireOutputUpdated(port);
        if (up && notifierId != null)
            NotificationUtil.getInstance().clearNotification(
                    notifierId);
    }

    @Override
    public void interfaceDown(NetInterface iface) {
        interfaceChange(iface, false);
    }

    @Override
    public void interfaceUp(NetInterface iface) {
        interfaceChange(iface, true);
    }

    @Override
    public void interfaceError(NetInterface iface,
            NetInterfaceErrorCode errCode) {
        if (!(iface instanceof StreamingNetInterface))
            return;

        StreamingNetInterface siface = (StreamingNetInterface) iface;
        String key = siface.streamId;
        Integer notifierId;
        TAKServer port;
        synchronized (streamPorts) {
            String appsKey = streamKeys.get(key);
            if (appsKey == null)
                return;

            notifierId = streamNotificationIds.get(appsKey);
            if (notifierId == null)
                return;

            port = streamPorts.get(appsKey);
            if (port == null)
                return;
        }

        String errMsg;
        switch (errCode) {
            case CONN_HOST_UNREACHABLE:
                errMsg = mapView.getContext().getString(
                        R.string.notification_text10);
                break;
            case CONN_NAME_RES_FAILED:
                errMsg = mapView.getContext().getString(
                        R.string.notification_text11);
                break;
            case CONN_REFUSED:
                errMsg = mapView.getContext().getString(
                        R.string.notification_text12);
                break;
            case CONN_SSL_HANDSHAKE:
                errMsg = mapView.getContext().getString(
                        R.string.notification_text13);
                break;
            case CONN_SSL_NO_PEER_CERT:
                errMsg = mapView.getContext().getString(
                        R.string.notification_text14);
                break;
            case CONN_SSL_PEER_CERT_NOT_TRUSTED:
                errMsg = mapView.getContext().getString(
                        R.string.notification_text15);
                break;
            case CONN_TIMEOUT:
                errMsg = mapView.getContext().getString(
                        R.string.notification_text16);
                break;
            case CONN_OTHER:
                errMsg = mapView.getContext().getString(
                        R.string.notification_text17);
                break;
            case INTERNAL:
                errMsg = mapView.getContext().getString(
                        R.string.notification_text18);
                break;
            case IO_RX_DATA_TIMEOUT:
                errMsg = mapView.getContext().getString(
                        R.string.notification_text19);
                break;
            case IO:
                errMsg = mapView.getContext().getString(
                        R.string.notification_text20);
                break;
            case OTHER:
            default:
                errMsg = mapView.getContext().getString(
                        R.string.notification_text21);
                break;
        }
        if (commo.hasFeature(CommsProvider.CommsFeature.ENDPOINT_SUPPORT)) {
            NotificationUtil.getInstance().postNotification(
                    notifierId,
                    NotificationUtil.GeneralIcon.NETWORK_ERROR.getID(),
                    NotificationUtil.RED,
                    mapView.getContext().getString(R.string.connection_error),
                    mapView.getContext().getString(R.string.error_connecting)
                            + port.getDescription() + " (" + errMsg
                            + ")",
                    null,
                    true);
        }

        port.setErrorString(errMsg);
    }

    public void setServerVersion(String connectString, ServerVersion version) {
        TAKServer port;
        synchronized (streamPorts) {
            port = streamPorts.get(connectString);
        }

        if (port == null) {
            Log.w(TAG, "setServerVersion, not found: " + connectString);
            return;
        }

        port.setServerVersion(version);
    }

    /**
     * Start a streaming server certificate enrollment.  The enrollment will proceed asynchronously
     * with progress and results posted to the provided EnrollmentListener. All parameters required
     * (non-null) unless otherwise noted.
     * @param listener EnrollmentListener to be provided with updates on progress and
     *                 results of the enrollment
     * @param host IP address or hostname of the server to enroll with.  If verifyHost is true,
     *             whatever form is provided will be checked against the certificate provided when
     *             making https connections during enrollment
     * @param port port number on server which corresponds to the server-side configured
     *             enrollment API port
     * @param verifyHost true to verify that the server's presented certificate identifies the same
     *                   host provided in the host parameter or false to skip such checks 
     * @param user username to use when authenticating to the enrollment API on the server as 
     *             as to embed in the certificate request; if using token auth, this is not used
     *             for auth but is still used in the certificate request and thus is still 
     *             a required parameter
     * @param password password for supplied username, or authentication token for token auth
     * @param passIsToken true for token based auth, false for password based auth
     * @param caCert pkcs12 certificate store to use as trust anchor when connecting to the server.
     *               Optional - Use null to trust all servers
     * @param caCertPassword password for certificate store provided in caCert.
     *                       Optional: may be null if caCert is null
     * @param clientCertPassword password to use when encrypting generated client certificate and
     *                           private key; these generated cert and key stores will be provided
     *                           to the provided EnrollmentListener
     * @param enrolledTrustPassword password to use when encrypting trust store containing trust
     *                              configuration provided by the server during enrollment; this
     *                              store is passed to the provided EnrollmentListener on
     *                              enrollment completion 
     * @throws CommoException if the provided arguments cannot be used to initialize the enrollment
     *                        operation; if this is thrown, no updates will be posted to the
     *                        provided listener 
     */
    public void enroll(EnrollmentListener listener, String host, int port,
            boolean verifyHost, String user, String password,
            boolean passIsToken, byte[] caCert, String caCertPassword,
            String clientCertPassword, String enrolledTrustPassword)
            throws CommoException {
        masterEnrollment.enroll(listener, host, port, verifyHost, user,
                password,
                passIsToken, caCert, caCertPassword, clientCertPassword,
                enrolledTrustPassword);
    }

    public String generateKey(String password) {
        if (!commo.hasFeature(CommsProvider.CommsFeature.CRYPTO)) {
            Log.d(TAG, "CommsProvider does not support default crypto");
            return null;
        }

        final int DEFAULT_KEY_LENGTH = 4096;

        int keyLength = DEFAULT_KEY_LENGTH;
        AtakPreferences prefs = AtakPreferences
                .getInstance(mapView.getContext());
        try {
            keyLength = Integer.parseInt(prefs.get(
                    "apiCertEnrollmentKeyLength",
                    Integer.toString(DEFAULT_KEY_LENGTH)));
            if (keyLength < 1) {
                keyLength = DEFAULT_KEY_LENGTH;
            }
        } catch (Exception e) {
            Log.w(TAG,
                    "Failed to parse apiCertEnrollmentKeyLength: " + keyLength);
            keyLength = DEFAULT_KEY_LENGTH;
        }

        return commo.generateKeyCryptoString(password, keyLength);
    }

    public String generateKeystore(String certPem,
            List<String> caPem, String privateKeyPem,
            String password, String friendlyName) {
        if (!commo.hasFeature(CommsProvider.CommsFeature.CRYPTO)) {
            Log.d(TAG, "CommsProvider does not support default crypto");
            return null;
        }
        return commo.generateKeystoreCryptoString(certPem,
                caPem, privateKeyPem, password, friendlyName);
    }

    public String generateCSR(Map<String, String> dnEntries,
            String privateKey, String password) {
        if (!commo.hasFeature(CommsProvider.CommsFeature.CRYPTO)) {
            Log.d(TAG, "CommsProvider does not support default crypto");
            return null;
        }
        return commo.generateCSRCryptoString(dnEntries,
                privateKey, password);
    }

    public CloudClient createCloudClient(CloudIO io, CloudIOProtocol proto,
            String host, int port, String basePath, String user,
            String password) {
        try {
            if (!(commo instanceof DefaultCommsProvider)) {
                Log.d(TAG, "CommsProvider does not support cloud connections");
                return null;
            }
            byte[] caCerts;
            final X509Certificate[] certs = CertificateManager.getInstance()
                    .getCertificates(CertificateManager.getInstance()
                            .getSystemTrustManager());
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(null, null);
            int n = 1;
            for (X509Certificate c : certs) {
                ks.setCertificateEntry(String.valueOf(n), c);
                n++;
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            String s = "";
            ks.store(baos, s.toCharArray());
            caCerts = baos.toByteArray();

            return createCloudClient(io, proto, host, port, basePath,
                    user, password, caCerts, s);
        } catch (Exception e) {
            Log.e(TAG, "Failed to create cloud client for " + host, e);
            return null;
        }
    }

    public CloudClient createCloudClient(CloudIO io, CloudIOProtocol proto,
            String host, int port, String basePath, String user,
            String password, byte[] caCerts, String s) {
        try {
            if (!(commo instanceof DefaultCommsProvider)) {
                Log.d(TAG, "CommsProvider does not support cloud connections");
                return null;
            }
            return ((DefaultCommsProvider) commo).createCloudClient(io, proto,
                    host, port, basePath,
                    user, password, caCerts, s);
        } catch (Exception e) {
            Log.e(TAG, "Failed to create cloud client for " + host, e);
            return null;
        }
    }

    public void destroyCloudClient(CloudClient client) {
        try {
            if (!(commo instanceof DefaultCommsProvider)) {
                Log.d(TAG, "CommsProvider does not support cloud connections");
            } else {
                ((DefaultCommsProvider) commo).destroyCloudClient(client);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to destroy cloud client", e);
        }
    }

    private BroadcastReceiver _credListener = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (CredentialsPreference.CREDENTIALS_UPDATED.equals(intent
                    .getAction())) {
                String credType = intent.getStringExtra("type");
                //see if password for TAK Server CA truststore or client store were updated
                if (AtakAuthenticationCredentials.TYPE_clientPassword
                        .equals(credType)
                        || AtakAuthenticationCredentials.TYPE_caPassword
                                .equals(credType)) {
                    // Inform communications system to re-acquire new credentials
                    reconnectStreams();
                }
            } else if (NetworkConnectionPreferenceFragment.CERTIFICATE_UPDATED
                    .equals(intent.getAction())) {
                String certType = intent.getStringExtra("type");
                //see if TAK Server CA truststore or client store were updated
                if (AtakCertificateDatabaseIFace.TYPE_TRUST_STORE_CA
                        .equals(certType)
                        || AtakCertificateDatabaseIFace.TYPE_CLIENT_CERTIFICATE
                                .equals(certType)) {
                    reconnectStreams();
                }
            }
        }
    };

    private void reconnectStreams() {
        final CommsMapComponent cmc = CommsMapComponent.getInstance();
        if (cmc != null) {
            final CotService cs = cmc.getCotService();
            if (cs != null) {
                cs.reconnectStreams();
            }
        }
    }

    // Listen for map components finished loading and then process deferred events
    private BroadcastReceiver _componentsListener = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Check if already loaded
            if (componentsLoaded)
                return;

            // Get deferred messages
            componentsLoaded = true;
            final List<Pair<String, String>> msgs;
            synchronized (deferredMessages) {
                msgs = new ArrayList<>(deferredMessages);
                deferredMessages.clear();
            }

            // Nothing to process
            if (msgs.isEmpty())
                return;

            // Process on separate thread to avoid UI lockup
            Thread thr = new Thread(TAG + " Deferred CoT") {
                @Override
                public void run() {
                    Log.d(TAG, "Processing " + msgs.size()
                            + " deferred CoT messages");
                    for (Pair<String, String> msg : msgs)
                        cotMessageReceived(msg.first, msg.second);
                }
            };
            thr.setPriority(Thread.NORM_PRIORITY);
            thr.start();
        }
    };

    private class MasterEnrollmentIO implements EnrollmentIO {
        private final Map<Integer, EnrollmentListener> activeEnrollments = new HashMap<>();

        public void enroll(EnrollmentListener listener, String host, int port,
                boolean verifyHost, String user, String password,
                boolean passIsToken, byte[] caCert, String caCertPassword,
                String clientCertPassword, String enrolledTrustPassword)
                throws CommoException {

            if (!(commo instanceof DefaultCommsProvider)) {
                Log.d(TAG, "CommsProvider does not support IO Enrollment");
                return;
            }

            String versionCode;
            try {
                versionCode = URLEncoder.encode(
                        ATAKConstants.getVersionName(),
                        FileSystemUtils.UTF8_CHARSET.name());
            } catch (UnsupportedEncodingException ex) {
                versionCode = null;
            }
            int id = ((DefaultCommsProvider) commo).enrollmentInit(host, port,
                    verifyHost, user,
                    password, passIsToken, caCert,
                    caCertPassword, clientCertPassword, enrolledTrustPassword,
                    clientCertPassword,
                    4096, versionCode);
            activeEnrollments.put(id, listener);
            ((DefaultCommsProvider) commo).enrollmentStart(id);
        }

        // EnrollmentIO implementation
        public void enrollmentUpdate(EnrollmentIOUpdate update) {
            if (update.status == SimpleFileIOStatus.INPROGRESS)
                return;

            EnrollmentListener listener;
            boolean isComplete = update.status != SimpleFileIOStatus.SUCCESS
                    || update.step == EnrollmentStep.SIGN;
            synchronized (activeEnrollments) {
                if (isComplete)
                    listener = activeEnrollments.remove(update.transferId);
                else
                    listener = activeEnrollments.get(update.transferId);
            }

            if (update.status != SimpleFileIOStatus.SUCCESS) {
                EnrollmentListener.ErrorState e;
                switch (update.status) {
                    case AUTH_ERROR:
                        e = EnrollmentListener.ErrorState.AUTH_ERROR;
                        break;
                    case HOST_RESOLUTION_FAIL:
                    case CONNECT_FAIL:
                        e = EnrollmentListener.ErrorState.CONNECTION_FAILURE;
                        break;
                    case SSL_UNTRUSTED_SERVER:
                        e = EnrollmentListener.ErrorState.SERVER_NOT_TRUSTED;
                        break;
                    default:
                        e = EnrollmentListener.ErrorState.OTHER;
                }
                if (listener != null)
                    listener.onEnrollmentErrored(e);
            } else if (update.step == EnrollmentStep.KEYGEN) {
                if (listener != null)
                    listener.onEnrollmentKeygenerated(update.privResult);
            } else if (update.step == EnrollmentStep.SIGN) {
                if (listener != null)
                    listener.onEnrollmentCompleted(update.privResult,
                            update.caResult);
            }
        }

    }

    private static class MPTransferInfo {
        final int xferId;
        // if null, then was xfer to server
        final Set<String> remainingRecipientUids;
        final MPSendListener listener;

        MPTransferInfo(int xferId, Set<String> recipientUids,
                MPSendListener listener) {
            this.listener = listener;
            this.xferId = xferId;
            this.remainingRecipientUids = recipientUids;
        }

        MPTransferInfo(int xferId, MPSendListener listener) {
            this(xferId, null, listener);
        }
    }

    public class MasterMPIO implements MissionPackageIO {
        private static final String MISSION_PACKAGE_CREDENTIAL_TYPE = "Mission Package Web Server";
        private final Map<Integer, MPTransferInfo> activeOutboundTransfers;
        private final Map<File, MPReceiver> activeInboundTransfers;

        private volatile MPReceiveInitiator receiveInitiator;

        MasterMPIO() {
            activeOutboundTransfers = new HashMap<>();
            activeInboundTransfers = new HashMap<>();
            receiveInitiator = null;
        }

        public void setRxInitiator(MPReceiveInitiator rxInit) {
            receiveInitiator = rxInit;
        }

        public boolean reconfigFileSharing() {
            if (commo == null)
                return false;

            boolean ret = reconfigLocalWebServer(httpsCertFile);
            // Now make MPs via server match
            commo.setMissionPackageViaServerEnabled(ret);
            return ret;
        }

        byte[] getHttpsServerCert() throws CommoException {
            if (IOProviderFactory.exists(httpsCertFile)) {
                FileInputStream fis = null;
                try {
                    Log.d(TAG, "HttpsCert examining existing cert file");
                    KeyStore p12 = KeyStore.getInstance("pkcs12");
                    fis = IOProviderFactory.getInputStream(httpsCertFile);

                    p12.load(fis, getHttpsCertStoreSecret().toCharArray());
                    Enumeration<String> aliases = p12.aliases();
                    int n = 0;
                    String alias = null;
                    while (aliases.hasMoreElements()) {
                        alias = aliases.nextElement();
                        n++;
                        Log.d(TAG, "HttpsCert Alias is \"" + alias + "\"");
                    }
                    if (n != 1)
                        throw new IllegalArgumentException(
                                "Certificate contains " + n
                                        + " aliases - we can only use files with one alias!");

                    Certificate cert = p12.getCertificate(alias);
                    X509Certificate xcert = (X509Certificate) cert;
                    xcert.checkValidity();
                    if ("1.2.840.113549.1.1.5".equals(xcert.getSigAlgOID()))
                        throw new IllegalArgumentException(
                                "Certificate is signed using legacy SHA-1 algorithm");

                    // All good
                    fis.close();
                    Log.d(TAG,
                            "HttpsCert existing file looks valid, re-using it");
                    fis = IOProviderFactory.getInputStream(httpsCertFile);
                    long len = IOProviderFactory.length(httpsCertFile);
                    if (len > Integer.MAX_VALUE)
                        throw new IllegalArgumentException(
                                "Existing cert is way too large!");
                    byte[] ret = new byte[(int) len];
                    len = 0;
                    while (len < ret.length) {
                        int r = fis.read(ret);
                        if (r == -1)
                            throw new IOException(
                                    "Could not fully read cert file");
                        len += r;
                    }
                    fis.close();
                    return ret;
                } catch (Exception ex) {
                    Log.d(TAG,
                            "HttpsCert problem with existing certificate file, generating new one",
                            ex);
                    if (fis != null)
                        try {
                            fis.close();
                        } catch (IOException ignored) {
                        }
                } finally {
                    IoUtils.close(fis);
                }
            }

            if (!IOProviderFactory.exists(httpsCertFile.getParentFile()))
                IOProviderFactory.mkdirs(httpsCertFile.getParentFile());

            // Generate new cert
            byte[] cert = new byte[0];
            if (commo.hasFeature(CommsProvider.CommsFeature.CRYPTO)) {
                cert = commo.generateSelfSignedCert(getHttpsCertStoreSecret());
            }
            try (OutputStream fos = IOProviderFactory
                    .getOutputStream(httpsCertFile)) {
                fos.write(cert);
                Log.d(TAG, "HttpsCert new cert stored for later use");
            } catch (IOException ex) {
                Log.e(TAG, "Could not write https certificate file", ex);
            }
            return cert;

        }

        private String getHttpsCertStoreSecret() {
            AtakAuthenticationCredentials mpCredentials = AtakAuthenticationDatabase
                    .getCredentials(MISSION_PACKAGE_CREDENTIAL_TYPE);

            if (mpCredentials != null
                    && !FileSystemUtils.isEmpty(mpCredentials.password)) {
                Log.d(TAG, "getHttpsCertStoreSecret exists");
                return mpCredentials.password;
            }

            Log.d(TAG, "getHttpsCertStoreSecret creating");
            String creds = ATAKUtilities.getRandomString(64);
            AtakAuthenticationDatabase.saveCredentials(
                    MISSION_PACKAGE_CREDENTIAL_TYPE, "", creds, false);
            return creds;
        }

        private boolean reconfigLocalWebServer(File certFile) {
            if (commo == null)
                return false;

            if (filesharingEnabled) {
                if (!nonStreamsEnabled) {
                    // Disable the local web server but return true anyway
                    // because we overall file shares are still enabled.
                    // Emulates the old pre-commo behavior of WebServer class

                    try {
                        commo.setMissionPackageLocalPort(
                                Commo.MPIO_LOCAL_PORT_DISABLE);
                        commo.setMissionPackageLocalHttpsParams(
                                Commo.MPIO_LOCAL_PORT_DISABLE, null, null);
                    } catch (CommoException ex) {
                        Log.e(TAG, "Error disabling local web server", ex);
                    }
                    return true;
                }

                try {
                    commo.setMissionPackageLocalPort(localWebServerPort);
                } catch (CommoException ex) {
                    Log.e(TAG, "Error setting local web server port "
                            + localWebServerPort
                            + " port may already be in use.  Local web server is disabled.",
                            ex);
                    return false;
                }

                try {
                    byte[] cert = getHttpsServerCert();
                    commo.setMissionPackageLocalHttpsParams(
                            secureLocalWebServerPort, cert,
                            getHttpsCertStoreSecret());
                } catch (CommoException ex) {
                    Log.e(TAG, "Error setting local https server port "
                            + secureLocalWebServerPort
                            + " port may already be in use or certs are invalid.  Local https server is disabled.",
                            ex);
                    // Delete the https certificate in case it was invalid
                    if (!FileSystemUtils.deleteFile(certFile)) {
                        Log.e(TAG, "could not delete certificate file: "
                                + certFile);
                    }
                    // Not considering this fatal error for now - it will be in the future
                }
                return true;

            } else {
                try {
                    commo.setMissionPackageLocalPort(
                            Commo.MPIO_LOCAL_PORT_DISABLE);
                    commo.setMissionPackageLocalHttpsParams(
                            Commo.MPIO_LOCAL_PORT_DISABLE, null, null);
                } catch (CommoException ex) {
                    Log.e(TAG, "Error disabling local web server", ex);
                }
                return false;
            }
        }

        @Override
        public String createUUID() {
            return UUID.randomUUID().toString();
        }

        @Override
        public CoTPointData getCurrentPoint() {
            GeoPoint point = mapView.getSelfMarker().getPoint();
            // XXY what about AGL
            return new CoTPointData(point.getLatitude(), point.getLongitude(),
                    point.getAltitude(), point.getCE(), point.getLE());
        }

        @Override
        public void missionPackageReceiveStatusUpdate(
                MissionPackageReceiveStatusUpdate update) {
            missionPackageReceiveStatusUpdate(
                    new CommsProvider.MissionPackageReceiveStatusUpdate(
                            update.localFile,
                            update.status,
                            update.totalBytesReceived,
                            update.totalBytesExpected,
                            update.attempt,
                            update.maxAttempts,
                            update.errorDetail));
        }

        public void missionPackageReceiveStatusUpdate(
                CommsProvider.MissionPackageReceiveStatusUpdate update) {
            MPReceiver rx;
            synchronized (activeInboundTransfers) {
                rx = activeInboundTransfers.get(update.localFile);
            }
            if (rx == null)
                return;

            switch (update.status) {
                case FINISHED_SUCCESS:
                    rx.receiveComplete(true, null, update.attempt);
                    break;
                case FINISHED_FAILED:
                    rx.receiveComplete(false, update.errorDetail,
                            update.attempt);
                    break;
                case ATTEMPT_FAILED:
                    rx.attemptFailed(update.errorDetail, update.attempt,
                            update.maxAttempts);
                    break;
                case ATTEMPT_IN_PROGRESS:
                    rx.receiveProgress(update.totalBytesReceived,
                            update.totalBytesExpected,
                            update.attempt,
                            update.maxAttempts);
                    break;
                default:
                    Log.w(TAG, "Unknown mp receive status code!");
            }
        }

        @Override
        public File missionPackageReceiveInit(String fileName,
                String transferName,
                String sha256hash,
                long byteLen,
                String senderCallsign)
                throws MissionPackageTransferException {
            MPReceiveInitiator rxInit = receiveInitiator;

            if (!filesharingEnabled || rxInit == null)
                throw new MissionPackageTransferException(
                        MissionPackageTransferStatus.FINISHED_DISABLED_LOCALLY);

            try {
                MPReceiver rx = rxInit.initiateReceive(fileName, transferName,
                        sha256hash,
                        byteLen, senderCallsign);
                if (rx == null)
                    throw new MissionPackageTransferException(
                            MissionPackageTransferStatus.FINISHED_FILE_EXISTS);

                File f = rx.getDestinationFile();
                synchronized (activeInboundTransfers) {
                    activeInboundTransfers.put(f, rx);
                }
                return f;

            } catch (IOException e) {
                throw new MissionPackageTransferException(
                        MissionPackageTransferStatus.FINISHED_FAILED);
            }
        }

        @Override
        public void missionPackageSendStatusUpdate(
                MissionPackageSendStatusUpdate statusUpdate) {
            missionPackageSendStatusUpdate(
                    new CommsProvider.MissionPackageSendStatusUpdate(
                            statusUpdate.transferId,
                            (statusUpdate.recipient != null)
                                    ? statusUpdate.recipient.contactUID
                                    : null,
                            statusUpdate.status,
                            statusUpdate.additionalDetail,
                            statusUpdate.totalBytesTransferred));
        }

        public void missionPackageSendStatusUpdate(
                CommsProvider.MissionPackageSendStatusUpdate statusUpdate) {
            // One of the transfers had some status change
            MPTransferInfo info;
            MPSendListener.UploadStatus uploadStatus = null;
            boolean sendComplete = false;
            boolean success = false;
            boolean isToServer = statusUpdate.recipientUid == null;
            String contactUid = statusUpdate.recipientUid;

            synchronized (activeOutboundTransfers) {
                info = activeOutboundTransfers.get(statusUpdate.transferId);

                if (info == null) {
                    Log.w(TAG, "MP Transfer update for unknown transfer id "
                            + statusUpdate.transferId + " - ignoring");
                    return;
                }

                switch (statusUpdate.status) {
                    case FINISHED_SUCCESS:
                        success = true;
                        sendComplete = true;
                        break;
                    case FINISHED_TIMED_OUT:
                    case FINISHED_FAILED:
                    case FINISHED_CONTACT_GONE:
                    case FINISHED_DISABLED_LOCALLY:
                        sendComplete = true;
                        break;
                    case SERVER_UPLOAD_FAILED:
                        uploadStatus = UploadStatus.FAILED;
                        break;
                    case SERVER_UPLOAD_IN_PROGRESS:
                        uploadStatus = UploadStatus.IN_PROGRESS;
                        break;
                    case SERVER_UPLOAD_PENDING:
                        uploadStatus = UploadStatus.PENDING;
                        break;
                    case SERVER_UPLOAD_SUCCESS:
                        if (statusUpdate.totalBytesTransferred == 0)
                            uploadStatus = UploadStatus.FILE_ALREADY_ON_SERVER;
                        else
                            uploadStatus = UploadStatus.COMPLETE;
                        break;
                    case ATTEMPT_IN_PROGRESS:
                        // default case in handling below
                        break;
                    default:
                        Log.w(TAG, "Unknown mp send status code!");
                        return;
                }

                if (sendComplete) {
                    if (info.remainingRecipientUids != null)
                        info.remainingRecipientUids
                                .remove(statusUpdate.recipientUid);

                    if (info.remainingRecipientUids == null
                            || info.remainingRecipientUids.isEmpty()) {
                        activeOutboundTransfers.remove(statusUpdate.transferId);
                    }
                }
            }

            if (info.listener == null)
                return;

            if (sendComplete) {
                if (success) {
                    if (!isToServer)
                        info.listener.mpAckReceived(contactUid,
                                statusUpdate.additionalDetail,
                                statusUpdate.totalBytesTransferred);
                } else
                    info.listener.mpSendFailed(contactUid,
                            statusUpdate.additionalDetail,
                            statusUpdate.totalBytesTransferred);

            } else if (uploadStatus != null) {
                info.listener.mpUploadProgress(
                        contactUid,
                        uploadStatus,
                        statusUpdate.additionalDetail,
                        statusUpdate.totalBytesTransferred);
            } else if (!isToServer) {
                // Progress notification
                info.listener
                        .mpSendInProgress(statusUpdate.recipientUid);
            }
        }

        void sendMissionPackage(String streamingId,
                File file, String transferFilename,
                MPSendListener listener) throws CommoException {
            int id = commo.sendMissionPackageInit(streamingId,
                    file, transferFilename);

            MPTransferInfo info = new MPTransferInfo(id, listener);
            synchronized (activeOutboundTransfers) {
                activeOutboundTransfers.put(id, info);
            }

            try {
                commo.startMissionPackageSend(id);
            } catch (CommoException ex) {
                synchronized (activeOutboundTransfers) {
                    activeOutboundTransfers.remove(id);
                }
                throw ex;
            }

        }

        void sendMissionPackage(Set<String> contacts, File file,
                String transferFilename,
                String transferName,
                MPSendListener listener) throws CommoException {
            AtakPreferences prefs = AtakPreferences
                    .getInstance(mapView.getContext());

            int id = commo.sendMissionPackageInit(contacts, file,
                    transferFilename, transferName,
                    prefs.get("mapitem.access", null),
                    prefs.get("mapitem.caveat", null),
                    prefs.get("mapitem.releasableTo", null));

            String[] sentToArray = new String[contacts.size()];
            int i = 0;
            for (String c : contacts)
                sentToArray[i++] = c;

            MPTransferInfo info = new MPTransferInfo(id, contacts, listener);
            synchronized (activeOutboundTransfers) {
                activeOutboundTransfers.put(id, info);
            }

            // Tell the listener about who actually is getting
            // this thing before starting up the transfer
            listener.mpSendRecipients(sentToArray);

            try {
                commo.startMissionPackageSend(id);
            } catch (CommoException ex) {
                synchronized (activeOutboundTransfers) {
                    activeOutboundTransfers.remove(id);
                }
                throw ex;
            }
        }

    }

    public class MasterFileIO implements SimpleFileIO {
        private final Map<Integer, CommsFileTransferListener> clientListeners;
        private final Map<Integer, SimpleFileIOUpdate> transferResults;

        public MasterFileIO() {
            clientListeners = new HashMap<>();
            transferResults = new HashMap<>();
        }

        @Override
        public void fileTransferUpdate(SimpleFileIOUpdate update) {
            if (update.status == SimpleFileIOStatus.INPROGRESS) {
                CommsFileTransferListener target;
                synchronized (clientListeners) {
                    target = clientListeners.get(update.transferId);
                }

                // Update listener
                if (target != null)
                    target.bytesTransferred(update.bytesTransferred,
                            update.totalBytesToTransfer);
            } else {
                // Finished transfer with either success or failure
                synchronized (transferResults) {
                    transferResults.put(update.transferId, update);
                    transferResults.notifyAll();
                }
            }
        }

        /// Wrapping around commo's simple file transfer for synchronous transfers with a callback. See Commo.simpleFileTransferInit()
        public void doFileTransfer(CommsFileTransferListener listener,
                boolean forUpload,
                URI uri, byte[] caCert, String caCertPassword,
                String username, String password, File localFile)
                throws CommoException, IOException, InterruptedException {
            int id = commo.simpleFileTransferInit(forUpload, uri, caCert,
                    caCertPassword, username, password, localFile);
            // Add pending transfer to our listener
            if (listener != null) {
                synchronized (clientListeners) {
                    clientListeners.put(id, listener);
                }
            }
            // Start the transfer 
            commo.simpleFileTransferStart(id);
            // Wait for completion callback
            SimpleFileIOUpdate r;
            synchronized (transferResults) {
                while ((r = transferResults.get(id)) == null) {
                    transferResults.wait();
                }
                transferResults.remove(id);
            }

            // Finished transfer
            String s;
            switch (r.status) {
                case AUTH_ERROR:
                case ACCESS_DENIED:
                    s = "Valid user credentials required";
                    break;
                case CONNECT_FAIL:
                    s = "Could not connect to server";
                    break;
                case HOST_RESOLUTION_FAIL:
                    s = "Unable to resolve server hostname";
                    break;
                case LOCAL_FILE_OPEN_FAILURE:
                    s = "Could not open local file";
                    break;
                case LOCAL_IO_ERROR:
                    s = "I/O error read/writing local file";
                    break;
                case SSL_OTHER_ERROR:
                    s = "SSL protocol error";
                    break;
                case SSL_UNTRUSTED_SERVER:
                    s = "Server cert not trusted - check trust store";
                    break;
                case TRANSFER_TIMEOUT:
                    s = "Transfer timed out";
                    break;
                case URL_INVALID:
                    s = "Invalid server path";
                    break;
                case URL_NO_RESOURCE:
                    s = "Path not accessible on server";
                    break;
                case URL_UNSUPPORTED:
                    s = "Protocol not supported";
                    break;

                case SUCCESS:
                    s = null;
                    break;
                case OTHER_ERROR:
                default:
                    s = "File transfer failed";
                    break;

            }
            if (s != null)
                throw new IOException(s);
        }
    }

    public void setMissionPackageHttpsPort(int missionPackageHttpsPort) {
        try {
            commo.setMissionPackageHttpsPort(missionPackageHttpsPort);
        } catch (CommoException e) {
            Log.e(TAG,
                    "setMissionPackageHttpsPort failed!",
                    e);
        }
    }

    public void setMissionPackageHttpPort(int missionPackageHttpPort) {
        try {
            commo.setMissionPackageHttpPort(missionPackageHttpPort);
        } catch (CommoException e) {
            Log.e(TAG,
                    "setMissionPackageHttpPort failed!",
                    e);
        }
    }

    private class VpnMonitor extends Thread {

        private volatile boolean cancelled = false;
        private boolean lastState = true;

        public void cancel() {
            cancelled = true;
        }

        public void run() {

            while (!cancelled) {
                boolean onlyStreamingWithVPN = AtakPreferences.getInstance(null)
                        .get("onlyStreamingWithVPN", false);
                if (onlyStreamingWithVPN) {
                    try {
                        final NetworkInterface ni = NetworkInterface
                                .getByName("tun0");
                        boolean up = ni != null && ni.isUp();
                        onStateChange(up);
                    } catch (Exception e) {
                        Log.e(TAG, "error monitoring the vpn connection");
                    }
                }
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException ignored) {
                }

            }

        }

        private synchronized void onStateChange(boolean state) {
            if (state == lastState)
                return;

            final TAKServer[] servers = TAKServerListener.getInstance()
                    .getServers();
            for (TAKServer ts : servers) {
                ts.setEnabled(state);
                CommsMapComponent cmc = CommsMapComponent.getInstance();
                if (cmc != null) {
                    CotService cs = cmc.getCotService();
                    if (cs != null)
                        cs.addStreaming(ts);
                }
            }
            lastState = state;
        }
    }

    private final VpnMonitor vpnMonitor = new VpnMonitor();
}
