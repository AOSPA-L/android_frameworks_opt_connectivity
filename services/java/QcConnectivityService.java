/*
 * Copyright (c) 2013 The Linux Foundation. All rights reserved
 * Not a Contribution.
 *
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server;

import static android.Manifest.permission.MANAGE_NETWORK_POLICY;
import static android.Manifest.permission.RECEIVE_DATA_ACTIVITY_CHANGE;
import static android.net.ConnectivityManager.CONNECTIVITY_ACTION;
import static android.net.ConnectivityManager.CONNECTIVITY_ACTION_IMMEDIATE;
import static android.net.ConnectivityManager.TYPE_BLUETOOTH;
import static android.net.ConnectivityManager.TYPE_DUMMY;
import static android.net.ConnectivityManager.TYPE_ETHERNET;
import static android.net.ConnectivityManager.TYPE_MOBILE;
import static android.net.ConnectivityManager.TYPE_WIFI;
import static android.net.ConnectivityManager.TYPE_WIMAX;
import static android.net.ConnectivityManager.getNetworkTypeName;
import static android.net.ConnectivityManager.isNetworkTypeValid;
import static android.net.NetworkPolicyManager.RULE_ALLOW_ALL;
import static android.net.NetworkPolicyManager.RULE_REJECT_METERED;

import android.bluetooth.BluetoothTetheringDataTracker;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.net.CaptivePortalTracker;
import android.net.ConnectivityManager;
import android.net.DummyDataStateTracker;
import android.net.EthernetDataTracker;
import android.net.IConnectivityManager;
import android.net.INetworkManagementEventObserver;
import android.net.INetworkPolicyListener;
import android.net.INetworkPolicyManager;
import android.net.INetworkStatsService;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.LinkProperties.CompareResult;
import android.net.MobileDataStateTracker;
import android.net.NetworkConfig;
import android.net.NetworkInfo;
import android.net.NetworkInfo.DetailedState;
import android.net.NetworkQuotaInfo;
import android.net.NetworkState;
import android.net.NetworkStateTracker;
import android.net.NetworkUtils;
import android.net.Proxy;
import android.net.ProxyProperties;
import android.net.RouteInfo;
import android.net.wifi.WifiStateTracker;
import android.net.wimax.WimaxManagerConstants;
import android.os.Binder;
import android.os.FileUtils;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.INetworkManagementService;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.security.Credentials;
import android.security.KeyStore;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Slog;
import android.util.SparseIntArray;
import android.util.Xml;

import com.android.internal.R;
import com.android.internal.net.LegacyVpnInfo;
import com.android.internal.net.VpnConfig;
import com.android.internal.net.VpnProfile;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.StateMachine;
import com.android.internal.util.IState;
import com.android.internal.util.State;
import com.android.server.AlarmManagerService;
import com.android.internal.util.XmlUtils;
import com.android.server.am.BatteryStatsService;
import com.android.server.ConnectivityService;
import com.android.server.connectivity.Nat464Xlat;
import com.android.server.connectivity.Tethering;
import com.android.server.connectivity.Vpn;
import com.android.server.net.BaseNetworkObserver;
import com.android.server.net.LockdownVpnTracker;
import com.android.server.power.PowerManagerService;
import com.google.android.collect.Lists;
import com.google.android.collect.Sets;

import dalvik.system.DexClassLoader;
import dalvik.system.PathClassLoader;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.InvocationTargetException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.GregorianCalendar;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;


/**
 * @hide
 */
public class QcConnectivityService extends ConnectivityService {
    /**
     * QCCS intents
     */
   /**
    * Broadcast intent to notify connectivity changes in dual
    * network mode
    */
    public static final String CONNECTIVITY_AVAILABLE = "CONNECTIVITY_AVAILABLE";
    /**
     * (ConnectivityManager) Network type that triggered broadcast
     * Retreieve with android.content.Intent.getIntExtra(String, int)
     */
    public static final String EXTRA_NETWORK_TYPE = "netType";

    private static final String TAG = "QcConnectivityService";

    private static final boolean DBG = true;
    private static final boolean VDBG = true;

    private static final boolean LOGD_RULES = false;

    // TODO: create better separation between radio types and network types

    // how long to wait before switching back to a radio's default network
    private static final int RESTORE_DEFAULT_NETWORK_DELAY = 1 * 60 * 1000;
    // system property that can override the above value
    private static final String NETWORK_RESTORE_DELAY_PROP_NAME =
            "android.telephony.apn-restore";

    // used in recursive route setting to add gateways for the host for which
    // a host route was requested.
    private static final int MAX_HOSTROUTE_CYCLE_COUNT = 10;

    private Tethering mTethering;
    private boolean mTetheringConfigValid = false;

    private KeyStore mKeyStore;

    private Vpn mVpn;
    private VpnCallback mVpnCallback = new VpnCallback();

    private boolean mLockdownEnabled;
    private LockdownVpnTracker mLockdownTracker;

    private Nat464Xlat mClat;

    /** Lock around {@link #mUidRules} and {@link #mMeteredIfaces}. */
    private Object mRulesLock = new Object();
    /** Currently active network rules by UID. */
    private SparseIntArray mUidRules = new SparseIntArray();
    /** Set of ifaces that are costly. */
    private HashSet<String> mMeteredIfaces = Sets.newHashSet();

    /**
     * Sometimes we want to refer to the individual network state
     * trackers separately, and sometimes we just want to treat them
     * abstractly.
     */
    private NetworkStateTracker mNetTrackers[];

    /* Handles captive portal check on a network */
    private CaptivePortalTracker mCaptivePortalTracker;

    /**
     * The link properties that define the current links
     */
    private LinkProperties mCurrentLinkProperties[];

    /**
     * A per Net list of the PID's that requested access to the net
     * used both as a refcount and for per-PID DNS selection
     */
    private List<Integer> mNetRequestersPids[];

    // priority order of the nettrackers
    // (excluding dynamically set mNetworkPreference)
    // TODO - move mNetworkTypePreference into this
    private int[] mPriorityList;

    private Context mContext;
    private int mNetworkPreference;
    private int mActiveDefaultNetwork = -1;
    // 0 is full bad, 100 is full good
    private int mDefaultInetCondition = 0;
    private int mDefaultInetConditionPublished = 0;
    private boolean mInetConditionChangeInFlight = false;
    private int mDefaultConnectionSequence = 0;

    private Object mDnsLock = new Object();
    private int mNumDnsEntries;
    private boolean mDnsOverridden = false;

    private boolean mTestMode;
    private static ConnectivityService sServiceInstance;

    private INetworkManagementService mNetd;
    private INetworkPolicyManager mPolicyManager;

    TelephonyManager mTelephonyManager;

    private static final int ENABLED  = 1;
    private static final int DISABLED = 0;

    private static final boolean ADD = true;
    private static final boolean REMOVE = false;

    private static final boolean TO_DEFAULT_TABLE = true;
    private static final boolean TO_SECONDARY_TABLE = false;
    private static final int MAX_NETWORK_STATE_TRACKER_EVENT = 100;
    /**
     * used internally as a delayed event to make us switch back to the
     * default network
     */
    private static final int EVENT_RESTORE_DEFAULT_NETWORK =
            MAX_NETWORK_STATE_TRACKER_EVENT + 1;

    /**
     * used internally to change our mobile data enabled flag
     */
    private static final int EVENT_CHANGE_MOBILE_DATA_ENABLED =
            MAX_NETWORK_STATE_TRACKER_EVENT + 2;

    /**
     * used internally to change our network preference setting
     * arg1 = networkType to prefer
     */
    private static final int EVENT_SET_NETWORK_PREFERENCE =
            MAX_NETWORK_STATE_TRACKER_EVENT + 3;

    /**
     * used internally to synchronize inet condition reports
     * arg1 = networkType
     * arg2 = condition (0 bad, 100 good)
     */
    private static final int EVENT_INET_CONDITION_CHANGE =
            MAX_NETWORK_STATE_TRACKER_EVENT + 4;

    /**
     * used internally to mark the end of inet condition hold periods
     * arg1 = networkType
     */
    private static final int EVENT_INET_CONDITION_HOLD_END =
            MAX_NETWORK_STATE_TRACKER_EVENT + 5;

    /**
     * used internally to set enable/disable cellular data
     * arg1 = ENBALED or DISABLED
     */
    private static final int EVENT_SET_MOBILE_DATA =
            MAX_NETWORK_STATE_TRACKER_EVENT + 7;

    /**
     * used internally to clear a wakelock when transitioning
     * from one net to another
     */
    private static final int EVENT_CLEAR_NET_TRANSITION_WAKELOCK =
            MAX_NETWORK_STATE_TRACKER_EVENT + 8;

    /**
     * used internally to reload global proxy settings
     */
    private static final int EVENT_APPLY_GLOBAL_HTTP_PROXY =
            MAX_NETWORK_STATE_TRACKER_EVENT + 9;

    /**
     * used internally to set external dependency met/unmet
     * arg1 = ENABLED (met) or DISABLED (unmet)
     * arg2 = NetworkType
     */
    private static final int EVENT_SET_DEPENDENCY_MET =
            MAX_NETWORK_STATE_TRACKER_EVENT + 10;

    /**
     * used internally to restore DNS properties back to the
     * default network
     */
    private static final int EVENT_RESTORE_DNS =
            MAX_NETWORK_STATE_TRACKER_EVENT + 11;

    /**
     * used internally to send a sticky broadcast delayed.
     */
    private static final int EVENT_SEND_STICKY_BROADCAST_INTENT =
            MAX_NETWORK_STATE_TRACKER_EVENT + 12;

    /**
     * Used internally to
     * {@link NetworkStateTracker#setPolicyDataEnable(boolean)}.
     */
    private static final int EVENT_SET_POLICY_DATA_ENABLE = MAX_NETWORK_STATE_TRACKER_EVENT + 13;

    private static final int EVENT_VPN_STATE_CHANGED = MAX_NETWORK_STATE_TRACKER_EVENT + 14;

    // list of DeathRecipients used to make sure features are turned off when
    // a process dies
    private List<FeatureUser> mFeatureUsers;

    private boolean mSystemReady;
    private Intent mInitialBroadcast;

    private PowerManager.WakeLock mNetTransitionWakeLock;
    private String mNetTransitionWakeLockCausedBy = "";
    private int mNetTransitionWakeLockSerialNumber;
    private int mNetTransitionWakeLockTimeout;

    private InetAddress mDefaultDns;

    // this collection is used to refcount the added routes - if there are none left
    // it's time to remove the route from the route table
    private Collection<RouteInfo> mAddedRoutes = new ArrayList<RouteInfo>();

    // used in DBG mode to track inet condition reports
    private static final int INET_CONDITION_LOG_MAX_SIZE = 25;
    private ArrayList mInetLog;

    // track the current default http proxy - tell the world if we get a new one (real change)
    private ProxyProperties mDefaultProxy = null;
    private Object mProxyLock = new Object();
    private boolean mDefaultProxyDisabled = false;

    // track the global proxy.
    private ProxyProperties mGlobalProxy = null;

    private SettingsObserver mSettingsObserver;

    NetworkConfig[] mNetConfigs;
    int mNetworksDefined;

    private static class RadioAttributes {
        public int mSimultaneity;
        public int mType;
        public RadioAttributes(String init) {
            String fragments[] = init.split(",");
            mType = Integer.parseInt(fragments[0]);
            mSimultaneity = Integer.parseInt(fragments[1]);
        }
    }
    RadioAttributes[] mRadioAttributes;

    // the set of network types that can only be enabled by system/sig apps
    List mProtectedNetworks;

    /**
     * QcCS specific declarations
     */

    Object cneObj = null;
    public static final int EVENT_UPDATE_BLOCKED_UID = 501;
    public static final int EVENT_REPRIORITIZE_DNS = 502;
    public static final int EVENT_CONNECTIVITY_SWITCH = 503;
    public static final int EVENT_AVOID_UNSUITABLE_WIFI = 504;

    private Handler mHandler;
    /**
     * HSM handler to handle all events in a given state
     */
    private ConnectivityServiceHSM mHSM;

    private int mRouteIdCtr = 0;

    private final int INVALID_MSG_ARG = -1;

    private final class RouteAttributes {
        /**
         * Class for holding identifiers used to create custom tables for source
         * policy routing in the kernel.
         */
        private int tableId;
        private int metric;

        public RouteAttributes () {
        //We are assuming that MAX network types supported on android won't
        //exceed 253 in which case identifier assignment needs to change. Its
        //safe to do it this way for now.
            tableId = ++mRouteIdCtr;
            metric = 0;
        }

        public int getTableId() {
            return tableId;
        }

        public int getMetric() {
            return metric;
        }

        public void setMetric(int m) {
            metric = m;
        }
    }
    private RouteAttributes[]  mRouteAttributes;
    //CNE Service Instance, resides in ConnectivityService
    Object mLinkManager = null;
    private boolean wqeEnabled = false;

    public QcConnectivityService(Context context, INetworkManagementService netd,
            INetworkStatsService statsService, INetworkPolicyManager policyManager) {
        // Currently, omitting a NetworkFactory will create one internally
        // TODO: create here when we have cleaner WiMAX support
        this(context, netd, statsService, policyManager, null);
    }

    public QcConnectivityService(Context context, INetworkManagementService netManager,
            INetworkStatsService statsService, INetworkPolicyManager policyManager,
            NetworkFactory netFactory) {
        if (DBG) log("QcConnectivityService starting up");

        //HSM uses routeAttributes. So initialize it here, prior to creating HSM
        mRouteAttributes = new RouteAttributes[ConnectivityManager.MAX_NETWORK_TYPE+1];
        for (int i = 0; i < ConnectivityManager.MAX_NETWORK_TYPE+1; i++) {
            mRouteAttributes[i] = new RouteAttributes();
        }

        int value = SystemProperties.getInt("persist.cne.feature", 0);
        // using values directly until featureconfig is available
        wqeEnabled = (value == 3 || value == 6);

        HandlerThread handlerThread = new HandlerThread("ConnectivityServiceThread");
        handlerThread.start();
        mHSM = new ConnectivityServiceHSM( mContext,
                                           "ConnectivityServiceHSM",
                                           handlerThread.getLooper() );
        mHSM.start();
        mHandler = mHSM.getHandler();

        if (netFactory == null) {
            netFactory = new DefaultNetworkFactory(context, mHandler);
        }

        // setup our unique device name
        if (TextUtils.isEmpty(SystemProperties.get("net.hostname"))) {
            String id = Settings.Secure.getString(context.getContentResolver(),
                    Settings.Secure.ANDROID_ID);
            if (id != null && id.length() > 0) {
                String name = new String("android-").concat(id);
                SystemProperties.set("net.hostname", name);
            }
        }

        // read our default dns server ip
        String dns = Settings.Global.getString(context.getContentResolver(),
                Settings.Global.DEFAULT_DNS_SERVER);
        if (dns == null || dns.length() == 0) {
            dns = context.getResources().getString(
                    com.android.internal.R.string.config_default_dns_server);
        }
        try {
            mDefaultDns = NetworkUtils.numericToInetAddress(dns);
        } catch (IllegalArgumentException e) {
            loge("Error setting defaultDns using " + dns);
        }

        mContext = checkNotNull(context, "missing Context");
        mNetd = checkNotNull(netManager, "missing INetworkManagementService");
        mPolicyManager = checkNotNull(policyManager, "missing INetworkPolicyManager");
        mKeyStore = KeyStore.getInstance();
        mTelephonyManager = (TelephonyManager) mContext.getSystemService(Context.TELEPHONY_SERVICE);

        try {
            mPolicyManager.registerListener(mPolicyListener);
        } catch (RemoteException e) {
            // ouch, no rules updates means some processes may never get network
            loge("unable to register INetworkPolicyListener" + e.toString());
        }

        final PowerManager powerManager = (PowerManager) context.getSystemService(
                Context.POWER_SERVICE);
        mNetTransitionWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
        mNetTransitionWakeLockTimeout = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_networkTransitionTimeout);

        mNetTrackers = new NetworkStateTracker[
                ConnectivityManager.MAX_NETWORK_TYPE+1];
        mCurrentLinkProperties = new LinkProperties[ConnectivityManager.MAX_NETWORK_TYPE+1];

        mRadioAttributes = new RadioAttributes[ConnectivityManager.MAX_RADIO_TYPE+1];
        mNetConfigs = new NetworkConfig[ConnectivityManager.MAX_NETWORK_TYPE+1];

        // Load device network attributes from resources
        String[] raStrings = context.getResources().getStringArray(
                com.android.internal.R.array.radioAttributes);
        for (String raString : raStrings) {
            RadioAttributes r = new RadioAttributes(raString);
            if (r.mType > ConnectivityManager.MAX_RADIO_TYPE) {
                loge("Error in radioAttributes - ignoring attempt to define type " + r.mType);
                continue;
            }
            if (mRadioAttributes[r.mType] != null) {
                loge("Error in radioAttributes - ignoring attempt to redefine type " +
                        r.mType);
                continue;
            }
            mRadioAttributes[r.mType] = r;
        }

        // TODO: What is the "correct" way to do determine if this is a wifi only device?
        boolean wifiOnly = SystemProperties.getBoolean("ro.radio.noril", false);
        log("wifiOnly=" + wifiOnly);
        String[] naStrings = context.getResources().getStringArray(
                com.android.internal.R.array.networkAttributes);
        for (String naString : naStrings) {
            try {
                NetworkConfig n = new NetworkConfig(naString);
                if (n.type > ConnectivityManager.MAX_NETWORK_TYPE) {
                    loge("Error in networkAttributes - ignoring attempt to define type " +
                            n.type);
                    continue;
                }
                if (wifiOnly && ConnectivityManager.isNetworkTypeMobile(n.type)) {
                    log("networkAttributes - ignoring mobile as this dev is wifiOnly " +
                            n.type);
                    continue;
                }
                if (mNetConfigs[n.type] != null) {
                    loge("Error in networkAttributes - ignoring attempt to redefine type " +
                            n.type);
                    continue;
                }
                if (mRadioAttributes[n.radio] == null) {
                    loge("Error in networkAttributes - ignoring attempt to use undefined " +
                            "radio " + n.radio + " in network type " + n.type);
                    continue;
                }
                mNetConfigs[n.type] = n;
                mNetworksDefined++;
            } catch(Exception e) {
                // ignore it - leave the entry null
            }
        }

        mProtectedNetworks = new ArrayList<Integer>();
        int[] protectedNetworks = context.getResources().getIntArray(
                com.android.internal.R.array.config_protectedNetworks);
        for (int p : protectedNetworks) {
            if ((mNetConfigs[p] != null) && (mProtectedNetworks.contains(p) == false)) {
                mProtectedNetworks.add(p);
            } else {
                if (DBG) loge("Ignoring protectedNetwork " + p);
            }
        }

        // high priority first
        mPriorityList = new int[mNetworksDefined];
        {
            int insertionPoint = mNetworksDefined-1;
            int currentLowest = 0;
            int nextLowest = 0;
            while (insertionPoint > -1) {
                for (NetworkConfig na : mNetConfigs) {
                    if (na == null) continue;
                    if (na.priority < currentLowest) continue;
                    if (na.priority > currentLowest) {
                        if (na.priority < nextLowest || nextLowest == 0) {
                            nextLowest = na.priority;
                        }
                        continue;
                    }
                    mPriorityList[insertionPoint--] = na.type;
                }
                currentLowest = nextLowest;
                nextLowest = 0;
            }
        }

        // Update mNetworkPreference according to user mannually first then overlay config.xml
        mNetworkPreference = getPersistedNetworkPreference();
        if (mNetworkPreference == -1) {
            for (int n : mPriorityList) {
                if (mNetConfigs[n].isDefault() && ConnectivityManager.isNetworkTypeValid(n)) {
                    mNetworkPreference = n;
                    break;
                }
            }
            if (mNetworkPreference == -1) {
                throw new IllegalStateException(
                        "You should set at least one default Network in config.xml!");
            }
        }

        mNetRequestersPids =
                (List<Integer> [])new ArrayList[ConnectivityManager.MAX_NETWORK_TYPE+1];
        for (int i : mPriorityList) {
            mNetRequestersPids[i] = new ArrayList<Integer>();
        }

        mFeatureUsers = new ArrayList<FeatureUser>();

        mTestMode = SystemProperties.get("cm.test.mode").equals("true")
                && SystemProperties.get("ro.build.type").equals("eng");

        // Create and start trackers for hard-coded networks
        for (int targetNetworkType : mPriorityList) {
            final NetworkConfig config = mNetConfigs[targetNetworkType];
            final NetworkStateTracker tracker;
            try {
                tracker = netFactory.createTracker(targetNetworkType, config);
                mNetTrackers[targetNetworkType] = tracker;
            } catch (IllegalArgumentException e) {
                Slog.e(TAG, "Problem creating " + getNetworkTypeName(targetNetworkType)
                        + " tracker: " + e);
                continue;
            }

            tracker.startMonitoring(context, mHandler);
            if (config.isDefault()) {
                tracker.reconnect();
            }
        }

        mTethering = new Tethering(mContext, mNetd, statsService, this, mHandler.getLooper());
        mTetheringConfigValid = ((mTethering.getTetherableUsbRegexs().length != 0 ||
                                  mTethering.getTetherableWifiRegexs().length != 0 ||
                                  mTethering.getTetherableBluetoothRegexs().length != 0) &&
                                 mTethering.getUpstreamIfaceTypes().length != 0);

        mVpn = new Vpn(mContext, mVpnCallback, mNetd, this);
        mVpn.startMonitoring(mContext, mHandler);

        mClat = new Nat464Xlat(mContext, mNetd, this, mHandler);

        try {
            mNetd.registerObserver(mTethering);
            mNetd.registerObserver(mDataActivityObserver);
            mNetd.registerObserver(mClat);
        } catch (RemoteException e) {
            loge("Error registering observer :" + e);
        }

        if (DBG) {
            mInetLog = new ArrayList();
        }

        mSettingsObserver = new SettingsObserver(mHandler, EVENT_APPLY_GLOBAL_HTTP_PROXY);
        mSettingsObserver.observe(mContext);

        mCaptivePortalTracker = CaptivePortalTracker.makeCaptivePortalTracker(mContext, this);
        loadGlobalProxy();

        startCne();
    }

    /**
     * Factory that creates {@link NetworkStateTracker} instances using given
     * {@link NetworkConfig}.
     */
    public interface NetworkFactory {
        public NetworkStateTracker createTracker(int targetNetworkType, NetworkConfig config);
    }

    private static class DefaultNetworkFactory implements NetworkFactory {
        private final Context mContext;
        private final Handler mTrackerHandler;

        public DefaultNetworkFactory(Context context, Handler trackerHandler) {
            mContext = context;
            mTrackerHandler = trackerHandler;
        }

        @Override
        public NetworkStateTracker createTracker(int targetNetworkType, NetworkConfig config) {
            switch (config.radio) {
                case TYPE_WIFI:
                    return new WifiStateTracker(targetNetworkType, config.name);
                case TYPE_MOBILE:
                    return new MobileDataStateTracker(targetNetworkType, config.name);
                case TYPE_DUMMY:
                    return new DummyDataStateTracker(targetNetworkType, config.name);
                case TYPE_BLUETOOTH:
                    return BluetoothTetheringDataTracker.getInstance();
                case TYPE_WIMAX:
                    return makeWimaxStateTracker(mContext, mTrackerHandler);
                case TYPE_ETHERNET:
                    return EthernetDataTracker.getInstance();
                default:
                    throw new IllegalArgumentException(
                            "Trying to create a NetworkStateTracker for an unknown radio type: "
                            + config.radio);
            }
        }
    }

    /**
     * Loads external WiMAX library and registers as system service, returning a
     * {@link NetworkStateTracker} for WiMAX. Caller is still responsible for
     * invoking {@link NetworkStateTracker#startMonitoring(Context, Handler)}.
     */
    private static NetworkStateTracker makeWimaxStateTracker(
            Context context, Handler trackerHandler) {
        // Initialize Wimax
        DexClassLoader wimaxClassLoader;
        Class wimaxStateTrackerClass = null;
        Class wimaxServiceClass = null;
        Class wimaxManagerClass;
        String wimaxJarLocation;
        String wimaxLibLocation;
        String wimaxManagerClassName;
        String wimaxServiceClassName;
        String wimaxStateTrackerClassName;

        NetworkStateTracker wimaxStateTracker = null;

        boolean isWimaxEnabled = context.getResources().getBoolean(
                com.android.internal.R.bool.config_wimaxEnabled);

        if (isWimaxEnabled) {
            try {
                wimaxJarLocation = context.getResources().getString(
                        com.android.internal.R.string.config_wimaxServiceJarLocation);
                wimaxLibLocation = context.getResources().getString(
                        com.android.internal.R.string.config_wimaxNativeLibLocation);
                wimaxManagerClassName = context.getResources().getString(
                        com.android.internal.R.string.config_wimaxManagerClassname);
                wimaxServiceClassName = context.getResources().getString(
                        com.android.internal.R.string.config_wimaxServiceClassname);
                wimaxStateTrackerClassName = context.getResources().getString(
                        com.android.internal.R.string.config_wimaxStateTrackerClassname);

                if (DBG) log("wimaxJarLocation: " + wimaxJarLocation);
                wimaxClassLoader =  new DexClassLoader(wimaxJarLocation,
                        new ContextWrapper(context).getCacheDir().getAbsolutePath(),
                        wimaxLibLocation, ClassLoader.getSystemClassLoader());

                try {
                    wimaxManagerClass = wimaxClassLoader.loadClass(wimaxManagerClassName);
                    wimaxStateTrackerClass = wimaxClassLoader.loadClass(wimaxStateTrackerClassName);
                    wimaxServiceClass = wimaxClassLoader.loadClass(wimaxServiceClassName);
                } catch (ClassNotFoundException ex) {
                    loge("Exception finding Wimax classes: " + ex.toString());
                    return null;
                }
            } catch(Resources.NotFoundException ex) {
                loge("Wimax Resources does not exist!!! ");
                return null;
            }

            try {
                if (DBG) log("Starting Wimax Service... ");

                Constructor wmxStTrkrConst = wimaxStateTrackerClass.getConstructor
                        (new Class[] {Context.class, Handler.class});
                wimaxStateTracker = (NetworkStateTracker) wmxStTrkrConst.newInstance(
                        context, trackerHandler);

                Constructor wmxSrvConst = wimaxServiceClass.getDeclaredConstructor
                        (new Class[] {Context.class, wimaxStateTrackerClass});
                wmxSrvConst.setAccessible(true);
                IBinder svcInvoker = (IBinder)wmxSrvConst.newInstance(context, wimaxStateTracker);
                wmxSrvConst.setAccessible(false);

                ServiceManager.addService(WimaxManagerConstants.WIMAX_SERVICE, svcInvoker);

            } catch(Exception ex) {
                loge("Exception creating Wimax classes: " + ex.toString());
                return null;
            }
        } else {
            loge("Wimax is not enabled or not added to the network attributes!!! ");
            return null;
        }

        return wimaxStateTracker;
    }

    /**
     * Sets the preferred network.
     * @param preference the new preference
     */
    @Override
    public void setNetworkPreference(int preference) {
        enforceChangePermission();

        mHandler.sendMessage(
                mHandler.obtainMessage(EVENT_SET_NETWORK_PREFERENCE, preference, 0));
    }

    @Override
    public int getNetworkPreference() {
        enforceAccessPermission();
        int preference;
        synchronized(this) {
            preference = mNetworkPreference;
        }
        return preference;
    }

    private void handleSetNetworkPreference(int preference) {
        if (ConnectivityManager.isNetworkTypeValid(preference) &&
                mNetConfigs[preference] != null &&
                mNetConfigs[preference].isDefault()) {
            if (mNetworkPreference != preference) {
                final ContentResolver cr = mContext.getContentResolver();
                Settings.Global.putInt(cr, Settings.Global.NETWORK_PREFERENCE, preference);
                synchronized(this) {
                    mNetworkPreference = preference;
                }
                enforcePreference();
            }
        }
    }

    private int getConnectivityChangeDelay() {
        final ContentResolver cr = mContext.getContentResolver();

        /** Check system properties for the default value then use secure settings value, if any. */
        int defaultDelay = SystemProperties.getInt(
                "conn." + Settings.Global.CONNECTIVITY_CHANGE_DELAY,
                ConnectivityManager.CONNECTIVITY_CHANGE_DELAY_DEFAULT);
        return Settings.Global.getInt(cr, Settings.Global.CONNECTIVITY_CHANGE_DELAY,
                defaultDelay);
    }

    private int getPersistedNetworkPreference() {
        final ContentResolver cr = mContext.getContentResolver();

        final int networkPrefSetting = Settings.Global
                .getInt(cr, Settings.Global.NETWORK_PREFERENCE, -1);

        return networkPrefSetting;
    }

    /**
     * Make the state of network connectivity conform to the preference settings
     * In this method, we only tear down a non-preferred network. Establishing
     * a connection to the preferred network is taken care of when we handle
     * the disconnect event from the non-preferred network
     * (see {@link #handleDisconnect(NetworkInfo)}).
     */
    private void enforcePreference() {
        if (mNetTrackers[mNetworkPreference].getNetworkInfo().isConnected())
            return;

        if (!mNetTrackers[mNetworkPreference].isAvailable())
            return;

        for (int t=0; t <= ConnectivityManager.MAX_RADIO_TYPE; t++) {
            if (t != mNetworkPreference && mNetTrackers[t] != null &&
                    mNetTrackers[t].getNetworkInfo().isConnected()) {
                if (DBG) {
                    log("tearing down " + mNetTrackers[t].getNetworkInfo() +
                            " in enforcePreference");
                }
                teardown(mNetTrackers[t]);
            }
        }
    }

    private boolean teardown(NetworkStateTracker netTracker) {
        if (netTracker.teardown()) {
            netTracker.setTeardownRequested(true);
            return true;
        } else {
            return false;
        }
    }

    /**
     * Check if UID should be blocked from using the network represented by the
     * given {@link NetworkStateTracker}.
     */
    private boolean isNetworkBlocked(NetworkStateTracker tracker, int uid) {
        final String iface = tracker.getLinkProperties().getInterfaceName();

        final boolean networkCostly;
        final int uidRules;
        synchronized (mRulesLock) {
            networkCostly = mMeteredIfaces.contains(iface);
            uidRules = mUidRules.get(uid, RULE_ALLOW_ALL);
        }

        if (networkCostly && (uidRules & RULE_REJECT_METERED) != 0) {
            return true;
        }

        // no restrictive rules; network is visible
        return false;
    }

    /**
     * Return a filtered {@link NetworkInfo}, potentially marked
     * {@link DetailedState#BLOCKED} based on
     * {@link #isNetworkBlocked(NetworkStateTracker, int)}.
     */
    private NetworkInfo getFilteredNetworkInfo(NetworkStateTracker tracker, int uid) {
        NetworkInfo info = tracker.getNetworkInfo();
        if (isNetworkBlocked(tracker, uid)) {
            // network is blocked; clone and override state
            info = new NetworkInfo(info);
            info.setDetailedState(DetailedState.BLOCKED, null, null);
        }
        if (mLockdownTracker != null) {
            info = mLockdownTracker.augmentNetworkInfo(info);
        }
        if (uid > 1000)
        {
            if (info.isConnected() == true &&
                    mActiveDefaultNetwork == TYPE_MOBILE &&
                    info.getType() == TYPE_WIFI) {
                info.setDetailedState(DetailedState.VERIFYING_POOR_LINK, null, null);
            }
        }
        return info;
    }

    /**
     * Return NetworkInfo for the active (i.e., connected) network interface.
     * It is assumed that at most one network is active at a time. If more
     * than one is active, it is indeterminate which will be returned.
     * @return the info for the active network, or {@code null} if none is
     * active
     */
    @Override
    public NetworkInfo getActiveNetworkInfo() {
        enforceAccessPermission();
        final int uid = Binder.getCallingUid();
        return getNetworkInfo(mActiveDefaultNetwork, uid);
    }

    @Override
    public NetworkInfo getActiveNetworkInfoUnfiltered() {
        enforceAccessPermission();
        if (isNetworkTypeValid(mActiveDefaultNetwork)) {
            final NetworkStateTracker tracker = mNetTrackers[mActiveDefaultNetwork];
            if (tracker != null) {
                return tracker.getNetworkInfo();
            }
        }
        return null;
    }

    @Override
    public NetworkInfo getActiveNetworkInfoForUid(int uid) {
        enforceConnectivityInternalPermission();
        return getNetworkInfo(mActiveDefaultNetwork, uid);
    }

    @Override
    public NetworkInfo getNetworkInfo(int networkType) {
        enforceAccessPermission();
        final int uid = Binder.getCallingUid();
        return getNetworkInfo(networkType, uid);
    }

    private NetworkInfo getNetworkInfo(int networkType, int uid) {
        NetworkInfo info = null;
        if (isNetworkTypeValid(networkType)) {
            final NetworkStateTracker tracker = mNetTrackers[networkType];
            if (tracker != null) {
                info = getFilteredNetworkInfo(tracker, uid);
            }
        }
        return info;
    }

    @Override
    public NetworkInfo[] getAllNetworkInfo() {
        enforceAccessPermission();
        final int uid = Binder.getCallingUid();
        final ArrayList<NetworkInfo> result = Lists.newArrayList();
        synchronized (mRulesLock) {
            for (NetworkStateTracker tracker : mNetTrackers) {
                if (tracker != null) {
                    result.add(getFilteredNetworkInfo(tracker, uid));
                }
            }
        }
        return result.toArray(new NetworkInfo[result.size()]);
    }

    @Override
    public boolean isNetworkSupported(int networkType) {
        enforceAccessPermission();
        return (isNetworkTypeValid(networkType) && (mNetTrackers[networkType] != null));
    }

    /**
     * Return LinkProperties for the active (i.e., connected) default
     * network interface.  It is assumed that at most one default network
     * is active at a time. If more than one is active, it is indeterminate
     * which will be returned.
     * @return the ip properties for the active network, or {@code null} if
     * none is active
     */
    @Override
    public LinkProperties getActiveLinkProperties() {
        return getLinkProperties(mActiveDefaultNetwork);
    }

    @Override
    public LinkProperties getLinkProperties(int networkType) {
        enforceAccessPermission();
        if (isNetworkTypeValid(networkType)) {
            final NetworkStateTracker tracker = mNetTrackers[networkType];
            if (tracker != null) {
                return tracker.getLinkProperties();
            }
        }
        return null;
    }

    @Override
    public NetworkState[] getAllNetworkState() {
        enforceAccessPermission();
        final int uid = Binder.getCallingUid();
        final ArrayList<NetworkState> result = Lists.newArrayList();
        synchronized (mRulesLock) {
            for (NetworkStateTracker tracker : mNetTrackers) {
                if (tracker != null) {
                    final NetworkInfo info = getFilteredNetworkInfo(tracker, uid);
                    result.add(new NetworkState(
                            info, tracker.getLinkProperties(), tracker.getLinkCapabilities()));
                }
            }
        }
        return result.toArray(new NetworkState[result.size()]);
    }

    private NetworkState getNetworkStateUnchecked(int networkType) {
        if (isNetworkTypeValid(networkType)) {
            final NetworkStateTracker tracker = mNetTrackers[networkType];
            if (tracker != null) {
                return new NetworkState(tracker.getNetworkInfo(), tracker.getLinkProperties(),
                        tracker.getLinkCapabilities());
            }
        }
        return null;
    }

    @Override
    public NetworkQuotaInfo getActiveNetworkQuotaInfo() {
        enforceAccessPermission();

        final long token = Binder.clearCallingIdentity();
        try {
            final NetworkState state = getNetworkStateUnchecked(mActiveDefaultNetwork);
            if (state != null) {
                try {
                    return mPolicyManager.getNetworkQuotaInfo(state);
                } catch (RemoteException e) {
                }
            }
            return null;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public boolean isActiveNetworkMetered() {
        enforceAccessPermission();
        final long token = Binder.clearCallingIdentity();
        try {
            return isNetworkMeteredUnchecked(mActiveDefaultNetwork);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private boolean isNetworkMeteredUnchecked(int networkType) {
        final NetworkState state = getNetworkStateUnchecked(networkType);
        if (state != null) {
            try {
                return mPolicyManager.isNetworkMetered(state);
            } catch (RemoteException e) {
            }
        }
        return false;
    }

    @Override
    public boolean setRadios(boolean turnOn) {
        boolean result = true;
        enforceChangePermission();
        for (NetworkStateTracker t : mNetTrackers) {
            if (t != null) result = t.setRadio(turnOn) && result;
        }
        return result;
    }

    @Override
    public boolean setRadio(int netType, boolean turnOn) {
        enforceChangePermission();
        if (!ConnectivityManager.isNetworkTypeValid(netType)) {
            return false;
        }
        NetworkStateTracker tracker = mNetTrackers[netType];
        return tracker != null && tracker.setRadio(turnOn);
    }

    private INetworkManagementEventObserver mDataActivityObserver = new BaseNetworkObserver() {
        @Override
        public void interfaceClassDataActivityChanged(String label, boolean active) {
            int deviceType = Integer.parseInt(label);
            sendDataActivityBroadcast(deviceType, active);
        }
    };

    /**
     * Used to notice when the calling process dies so we can self-expire
     *
     * Also used to know if the process has cleaned up after itself when
     * our auto-expire timer goes off.  The timer has a link to an object.
     *
     */
    private class FeatureUser implements IBinder.DeathRecipient {
        int mNetworkType;
        String mFeature;
        IBinder mBinder;
        int mPid;
        int mUid;
        long mCreateTime;

        FeatureUser(int type, String feature, IBinder binder) {
            super();
            mNetworkType = type;
            mFeature = feature;
            mBinder = binder;
            mPid = getCallingPid();
            mUid = getCallingUid();
            mCreateTime = System.currentTimeMillis();

            try {
                mBinder.linkToDeath(this, 0);
            } catch (RemoteException e) {
                binderDied();
            }
        }

        void unlinkDeathRecipient() {
            mBinder.unlinkToDeath(this, 0);
        }

        public void binderDied() {
            log("ConnectivityService FeatureUser binderDied(" +
                    mNetworkType + ", " + mFeature + ", " + mBinder + "), created " +
                    (System.currentTimeMillis() - mCreateTime) + " mSec ago");
            stopUsingNetworkFeature(this, false);
        }

        public void expire() {
            if (VDBG) {
                log("ConnectivityService FeatureUser expire(" +
                        mNetworkType + ", " + mFeature + ", " + mBinder +"), created " +
                        (System.currentTimeMillis() - mCreateTime) + " mSec ago");
            }
            stopUsingNetworkFeature(this, false);
        }

        public boolean isSameUser(FeatureUser u) {
            if (u == null) return false;

            return isSameUser(u.mPid, u.mUid, u.mNetworkType, u.mFeature);
        }

        public boolean isSameUser(int pid, int uid, int networkType, String feature) {
            if ((mPid == pid) && (mUid == uid) && (mNetworkType == networkType) &&
                TextUtils.equals(mFeature, feature)) {
                return true;
            }
            return false;
        }

        public String toString() {
            return "FeatureUser("+mNetworkType+","+mFeature+","+mPid+","+mUid+"), created " +
                    (System.currentTimeMillis() - mCreateTime) + " mSec ago";
        }
    }

    // javadoc from interface
    @Override
    public int startUsingNetworkFeature(int networkType, String feature,
            IBinder binder) {
        long startTime = 0;
        if (DBG) {
            startTime = SystemClock.elapsedRealtime();
        }
        if (VDBG) {
            log("startUsingNetworkFeature for net " + networkType + ": " + feature + ", uid="
                    + Binder.getCallingUid());
        }
        enforceChangePermission();
        try {
            if (!ConnectivityManager.isNetworkTypeValid(networkType) ||
                    mNetConfigs[networkType] == null) {
                return PhoneConstants.APN_REQUEST_FAILED;
            }

            FeatureUser f = new FeatureUser(networkType, feature, binder);

            // TODO - move this into individual networktrackers
            int usedNetworkType = convertFeatureToNetworkType(networkType, feature);

            if (mLockdownEnabled) {
                // Since carrier APNs usually aren't available from VPN
                // endpoint, mark them as unavailable.
                return PhoneConstants.APN_TYPE_NOT_AVAILABLE;
            }

            if (mProtectedNetworks.contains(usedNetworkType)) {
                enforceConnectivityInternalPermission();
            }

            // if UID is restricted, don't allow them to bring up metered APNs
            final boolean networkMetered = isNetworkMeteredUnchecked(usedNetworkType);
            final int uidRules;
            synchronized (mRulesLock) {
                uidRules = mUidRules.get(Binder.getCallingUid(), RULE_ALLOW_ALL);
            }
            if (networkMetered && (uidRules & RULE_REJECT_METERED) != 0) {
                return PhoneConstants.APN_REQUEST_FAILED;
            }

            NetworkStateTracker network = mNetTrackers[usedNetworkType];
            if (network != null) {
                Integer currentPid = new Integer(getCallingPid());
                if (usedNetworkType != networkType) {
                    NetworkInfo ni = network.getNetworkInfo();

                    if (ni.isAvailable() == false) {
                        if (!TextUtils.equals(feature,Phone.FEATURE_ENABLE_DUN_ALWAYS)) {
                            if (DBG) log("special network not available ni=" + ni.getTypeName());
                            return PhoneConstants.APN_TYPE_NOT_AVAILABLE;
                        } else {
                            // else make the attempt anyway - probably giving REQUEST_STARTED below
                            if (DBG) {
                                log("special network not available, but try anyway ni=" +
                                        ni.getTypeName());
                            }
                        }
                    }

                    int restoreTimer = getRestoreDefaultNetworkDelay(usedNetworkType);

                    synchronized(this) {
                        boolean addToList = true;
                        if (restoreTimer < 0) {
                            // In case there is no timer is specified for the feature,
                            // make sure we don't add duplicate entry with the same request.
                            for (FeatureUser u : mFeatureUsers) {
                                if (u.isSameUser(f)) {
                                    // Duplicate user is found. Do not add.
                                    addToList = false;
                                    break;
                                }
                            }
                        }

                        if (addToList) mFeatureUsers.add(f);
                        if (!mNetRequestersPids[usedNetworkType].contains(currentPid)) {
                            // this gets used for per-pid dns when connected
                            mNetRequestersPids[usedNetworkType].add(currentPid);
                        }
                    }

                    if (restoreTimer >= 0) {
                        mHandler.sendMessageDelayed(mHandler.obtainMessage(
                                EVENT_RESTORE_DEFAULT_NETWORK, f), restoreTimer);
                    }

                    if ((ni.isConnectedOrConnecting() == true) &&
                            !network.isTeardownRequested()) {
                        if (ni.isConnected() == true) {
                            final long token = Binder.clearCallingIdentity();
                            try {
                                // add the pid-specific dns
                                handleDnsConfigurationChange(usedNetworkType);
                                if (VDBG) log("special network already active");
                            } finally {
                                Binder.restoreCallingIdentity(token);
                            }
                            return PhoneConstants.APN_ALREADY_ACTIVE;
                        }
                        if (VDBG) log("special network already connecting");
                        return PhoneConstants.APN_REQUEST_STARTED;
                    }

                    // check if the radio in play can make another contact
                    // assume if cannot for now

                    if (DBG) {
                        log("startUsingNetworkFeature reconnecting to " + networkType + ": " +
                                feature);
                    }
                    if (network.reconnect()) {
                        return PhoneConstants.APN_REQUEST_STARTED;
                    } else {
                        return PhoneConstants.APN_REQUEST_FAILED;
                    }
                } else {
                    // need to remember this unsupported request so we respond appropriately on stop
                    synchronized(this) {
                        mFeatureUsers.add(f);
                        if (!mNetRequestersPids[usedNetworkType].contains(currentPid)) {
                            // this gets used for per-pid dns when connected
                            mNetRequestersPids[usedNetworkType].add(currentPid);
                        }
                    }
                    return -1;
                }
            }
            return PhoneConstants.APN_TYPE_NOT_AVAILABLE;
         } finally {
            if (DBG) {
                final long execTime = SystemClock.elapsedRealtime() - startTime;
                if (execTime > 250) {
                    loge("startUsingNetworkFeature took too long: " + execTime + "ms");
                } else {
                    if (VDBG) log("startUsingNetworkFeature took " + execTime + "ms");
                }
            }
         }
    }

    // javadoc from interface
    @Override
    public int stopUsingNetworkFeature(int networkType, String feature) {
        enforceChangePermission();

        int pid = getCallingPid();
        int uid = getCallingUid();

        FeatureUser u = null;
        boolean found = false;

        synchronized(this) {
            for (FeatureUser x : mFeatureUsers) {
                if (x.isSameUser(pid, uid, networkType, feature)) {
                    u = x;
                    found = true;
                    break;
                }
            }
        }
        if (found && u != null) {
            // stop regardless of how many other time this proc had called start
            return stopUsingNetworkFeature(u, true);
        } else {
            // none found!
            if (VDBG) log("stopUsingNetworkFeature - not a live request, ignoring");
            return 1;
        }
    }

    private int stopUsingNetworkFeature(FeatureUser u, boolean ignoreDups) {
        int networkType = u.mNetworkType;
        String feature = u.mFeature;
        int pid = u.mPid;
        int uid = u.mUid;

        NetworkStateTracker tracker = null;
        boolean callTeardown = false;  // used to carry our decision outside of sync block

        if (VDBG) {
            log("stopUsingNetworkFeature: net " + networkType + ": " + feature);
        }

        if (!ConnectivityManager.isNetworkTypeValid(networkType)) {
            if (DBG) {
                log("stopUsingNetworkFeature: net " + networkType + ": " + feature +
                        ", net is invalid");
            }
            return -1;
        }

        // need to link the mFeatureUsers list with the mNetRequestersPids state in this
        // sync block
        synchronized(this) {
            // check if this process still has an outstanding start request
            if (!mFeatureUsers.contains(u)) {
                if (VDBG) {
                    log("stopUsingNetworkFeature: this process has no outstanding requests" +
                        ", ignoring");
                }
                return 1;
            }
            u.unlinkDeathRecipient();
            mFeatureUsers.remove(mFeatureUsers.indexOf(u));
            // If we care about duplicate requests, check for that here.
            //
            // This is done to support the extension of a request - the app
            // can request we start the network feature again and renew the
            // auto-shutoff delay.  Normal "stop" calls from the app though
            // do not pay attention to duplicate requests - in effect the
            // API does not refcount and a single stop will counter multiple starts.
            if (ignoreDups == false) {
                for (FeatureUser x : mFeatureUsers) {
                    if (x.isSameUser(u)) {
                        if (VDBG) log("stopUsingNetworkFeature: dup is found, ignoring");
                        return 1;
                    }
                }
            }

            // TODO - move to individual network trackers
            int usedNetworkType = convertFeatureToNetworkType(networkType, feature);

            tracker =  mNetTrackers[usedNetworkType];
            if (tracker == null) {
                if (DBG) {
                    log("stopUsingNetworkFeature: net " + networkType + ": " + feature +
                            " no known tracker for used net type " + usedNetworkType);
                }
                return -1;
            }
            if (usedNetworkType != networkType) {
                Integer currentPid = new Integer(pid);
                mNetRequestersPids[usedNetworkType].remove(currentPid);

                final long token = Binder.clearCallingIdentity();
                try {
                    reassessPidDns(pid, true);
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
                flushVmDnsCache();
                if (mNetRequestersPids[usedNetworkType].size() != 0) {
                    if (VDBG) {
                        log("stopUsingNetworkFeature: net " + networkType + ": " + feature +
                                " others still using it");
                    }
                    return 1;
                }
                callTeardown = true;
            } else {
                if (DBG) {
                    log("stopUsingNetworkFeature: net " + networkType + ": " + feature +
                            " not a known feature - dropping");
                }
            }
        }

        if (callTeardown) {
            if (DBG) {
                log("stopUsingNetworkFeature: teardown net " + networkType + ": " + feature);
            }
            tracker.teardown();
            return 1;
        } else {
            return -1;
        }
    }

    /**
     * @deprecated use requestRouteToHostAddress instead
     *
     * Ensure that a network route exists to deliver traffic to the specified
     * host via the specified network interface.
     * @param networkType the type of the network over which traffic to the
     * specified host is to be routed
     * @param hostAddress the IP address of the host to which the route is
     * desired
     * @return {@code true} on success, {@code false} on failure
     */
    public boolean requestRouteToHost(int networkType, int hostAddress) {
        InetAddress inetAddress = NetworkUtils.intToInetAddress(hostAddress);

        if (inetAddress == null) {
            return false;
        }

        return requestRouteToHostAddress(networkType, inetAddress.getAddress());
    }

    /**
     * Ensure that a network route exists to deliver traffic to the specified
     * host via the specified network interface.
     * @param networkType the type of the network over which traffic to the
     * specified host is to be routed
     * @param hostAddress the IP address of the host to which the route is
     * desired
     * @return {@code true} on success, {@code false} on failure
     */
    @Override
    public boolean requestRouteToHostAddress(int networkType, byte[] hostAddress) {
        enforceChangePermission();
        if (mProtectedNetworks.contains(networkType)) {
            enforceConnectivityInternalPermission();
        }

        if (!ConnectivityManager.isNetworkTypeValid(networkType)) {
            if (DBG) log("requestRouteToHostAddress on invalid network: " + networkType);
            return false;
        }
        NetworkStateTracker tracker = mNetTrackers[networkType];
        DetailedState netState = tracker.getNetworkInfo().getDetailedState();

        if (tracker == null || (netState != DetailedState.CONNECTED &&
                netState != DetailedState.CAPTIVE_PORTAL_CHECK) ||
                tracker.isTeardownRequested()) {
            if (VDBG) {
                log("requestRouteToHostAddress on down network " +
                           "(" + networkType + ") - dropped");
            }
            return false;
        }
        final long token = Binder.clearCallingIdentity();
        try {
            InetAddress addr = InetAddress.getByAddress(hostAddress);
            LinkProperties lp = tracker.getLinkProperties();
            return addRouteToAddress(lp, addr);
        } catch (UnknownHostException e) {
            if (DBG) log("requestRouteToHostAddress got " + e.toString());
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        return false;
    }

    private boolean addRoute(LinkProperties p, RouteInfo r, boolean toDefaultTable) {
        return modifyRoute(p, r, 0, ADD, toDefaultTable);
    }

    private boolean removeRoute(LinkProperties p, RouteInfo r, boolean toDefaultTable) {
        return modifyRoute(p, r, 0, REMOVE, toDefaultTable);
    }

    private boolean addRouteToAddress(LinkProperties lp, InetAddress addr) {
        return modifyRouteToAddress(lp, addr, ADD, TO_DEFAULT_TABLE);
    }

    private boolean removeRouteToAddress(LinkProperties lp, InetAddress addr) {
        return modifyRouteToAddress(lp, addr, REMOVE, TO_DEFAULT_TABLE);
    }

    private boolean modifyRouteToAddress(LinkProperties lp, InetAddress addr, boolean doAdd,
            boolean toDefaultTable) {
        RouteInfo bestRoute = RouteInfo.selectBestRoute(lp.getAllRoutes(), addr);
        if (bestRoute == null) {
            bestRoute = RouteInfo.makeHostRoute(addr, lp.getInterfaceName());
        } else {
            String iface = bestRoute.getInterface();
            if (bestRoute.getGateway().equals(addr)) {
                // if there is no better route, add the implied hostroute for our gateway
                bestRoute = RouteInfo.makeHostRoute(addr, iface);
            } else {
                // if we will connect to this through another route, add a direct route
                // to it's gateway
                bestRoute = RouteInfo.makeHostRoute(addr, bestRoute.getGateway(), iface);
            }
        }
        return modifyRoute(lp, bestRoute, 0, doAdd, toDefaultTable);
    }

    private boolean modifyRoute(LinkProperties lp, RouteInfo r, int cycleCount, boolean doAdd,
            boolean toDefaultTable) {
        if ((lp == null) || (r == null)) {
            if (DBG) log("modifyRoute got unexpected null: " + lp + ", " + r);
            return false;
        }

        if (cycleCount > MAX_HOSTROUTE_CYCLE_COUNT) {
            loge("Error modifying route - too much recursion");
            return false;
        }

        String ifaceName = r.getInterface();
        if(ifaceName == null) {
            loge("Error modifying route - no interface name");
            return false;
        }
        if (r.hasGateway()) {
            RouteInfo bestRoute = RouteInfo.selectBestRoute(lp.getAllRoutes(), r.getGateway());
            if (bestRoute != null) {
                if (bestRoute.getGateway().equals(r.getGateway())) {
                    // if there is no better route, add the implied hostroute for our gateway
                    bestRoute = RouteInfo.makeHostRoute(r.getGateway(), ifaceName);
                } else {
                    // if we will connect to our gateway through another route, add a direct
                    // route to it's gateway
                    bestRoute = RouteInfo.makeHostRoute(r.getGateway(),
                                                        bestRoute.getGateway(),
                                                        ifaceName);
                }
                modifyRoute(lp, bestRoute, cycleCount+1, doAdd, toDefaultTable);
            }
        }
        if (doAdd) {
            if (VDBG) log("Adding " + r + " for interface " + ifaceName);
            try {
                if (toDefaultTable) {
                    mAddedRoutes.add(r);  // only track default table - only one apps can effect
                    mNetd.addRoute(ifaceName, r);
                } else {
                    mNetd.addSecondaryRoute(ifaceName, r);
                }
            } catch (Exception e) {
                // never crash - catch them all
                if (DBG) loge("Exception trying to add a route: " + e);
                return false;
            }
        } else {
            // if we remove this one and there are no more like it, then refcount==0 and
            // we can remove it from the table
            if (toDefaultTable) {
                mAddedRoutes.remove(r);
                if (mAddedRoutes.contains(r) == false) {
                    if (VDBG) log("Removing " + r + " for interface " + ifaceName);
                    try {
                        mNetd.removeRoute(ifaceName, r);
                    } catch (Exception e) {
                        // never crash - catch them all
                        if (VDBG) loge("Exception trying to remove a route: " + e);
                        return false;
                    }
                } else {
                    if (VDBG) log("not removing " + r + " as it's still in use");
                }
            } else {
                if (VDBG) log("Removing " + r + " for interface " + ifaceName);
                try {
                    mNetd.removeSecondaryRoute(ifaceName, r);
                } catch (Exception e) {
                    // never crash - catch them all
                    if (VDBG) loge("Exception trying to remove a route: " + e);
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * @see ConnectivityManager#getMobileDataEnabled()
     */
    @Override
    public boolean getMobileDataEnabled() {
        // TODO: This detail should probably be in DataConnectionTracker's
        //       which is where we store the value and maybe make this
        //       asynchronous.
        enforceAccessPermission();
        boolean retVal = Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.MOBILE_DATA, 1) == 1;
        if (VDBG) log("getMobileDataEnabled returning " + retVal);
        return retVal;
    }

    @Override
    public void setDataDependency(int networkType, boolean met) {
        enforceConnectivityInternalPermission();

        mHandler.sendMessage(mHandler.obtainMessage(EVENT_SET_DEPENDENCY_MET,
                (met ? ENABLED : DISABLED), networkType));
    }

    private void handleSetDependencyMet(int networkType, boolean met) {
        if (mNetTrackers[networkType] != null) {
            if (DBG) {
                log("handleSetDependencyMet(" + networkType + ", " + met + ")");
            }
            mNetTrackers[networkType].setDependencyMet(met);
        }
    }

    private INetworkPolicyListener mPolicyListener = new INetworkPolicyListener.Stub() {
        @Override
        public void onUidRulesChanged(int uid, int uidRules) {
            // caller is NPMS, since we only register with them
            if (LOGD_RULES) {
                log("onUidRulesChanged(uid=" + uid + ", uidRules=" + uidRules + ")");
            }

            synchronized (mRulesLock) {
                // skip update when we've already applied rules
                final int oldRules = mUidRules.get(uid, RULE_ALLOW_ALL);
                if (oldRules == uidRules) return;

                mUidRules.put(uid, uidRules);
            }

            // TODO: notify UID when it has requested targeted updates
        }

        @Override
        public void onMeteredIfacesChanged(String[] meteredIfaces) {
            // caller is NPMS, since we only register with them
            if (LOGD_RULES) {
                log("onMeteredIfacesChanged(ifaces=" + Arrays.toString(meteredIfaces) + ")");
            }

            synchronized (mRulesLock) {
                mMeteredIfaces.clear();
                for (String iface : meteredIfaces) {
                    mMeteredIfaces.add(iface);
                }
            }
        }

        @Override
        public void onRestrictBackgroundChanged(boolean restrictBackground) {
            // caller is NPMS, since we only register with them
            if (LOGD_RULES) {
                log("onRestrictBackgroundChanged(restrictBackground=" + restrictBackground + ")");
            }

            // kick off connectivity change broadcast for active network, since
            // global background policy change is radical.
            final int networkType = mActiveDefaultNetwork;
            if (isNetworkTypeValid(networkType)) {
                final NetworkStateTracker tracker = mNetTrackers[networkType];
                if (tracker != null) {
                    final NetworkInfo info = tracker.getNetworkInfo();
                    if (info != null && info.isConnected()) {
                        sendConnectedBroadcast(info);
                    }
                }
            }
        }
    };

    /**
     * @see ConnectivityManager#setMobileDataEnabled(boolean)
     */
    @Override
    public void setMobileDataEnabled(boolean enabled) {
        enforceChangePermission();
        if (DBG) log("setMobileDataEnabled(" + enabled + ")");

        mHandler.sendMessage(mHandler.obtainMessage(EVENT_SET_MOBILE_DATA,
                (enabled ? ENABLED : DISABLED), 0));
    }

    private void handleSetMobileData(boolean enabled) {
        if (mNetTrackers[ConnectivityManager.TYPE_MOBILE] != null) {
            if (VDBG) {
                log(mNetTrackers[ConnectivityManager.TYPE_MOBILE].toString() + enabled);
            }
            mNetTrackers[ConnectivityManager.TYPE_MOBILE].setUserDataEnable(enabled);
        }
        if (mNetTrackers[ConnectivityManager.TYPE_WIMAX] != null) {
            if (VDBG) {
                log(mNetTrackers[ConnectivityManager.TYPE_WIMAX].toString() + enabled);
            }
            mNetTrackers[ConnectivityManager.TYPE_WIMAX].setUserDataEnable(enabled);
        }
    }

    @Override
    public void setPolicyDataEnable(int networkType, boolean enabled) {
        // only someone like NPMS should only be calling us
        mContext.enforceCallingOrSelfPermission(MANAGE_NETWORK_POLICY, TAG);

        mHandler.sendMessage(mHandler.obtainMessage(
                EVENT_SET_POLICY_DATA_ENABLE, networkType, (enabled ? ENABLED : DISABLED)));
    }

    private void handleSetPolicyDataEnable(int networkType, boolean enabled) {
        if (isNetworkTypeValid(networkType)) {
            final NetworkStateTracker tracker = mNetTrackers[networkType];
            if (tracker != null) {
                tracker.setPolicyDataEnable(enabled);
            }
        }
    }

    private void enforceAccessPermission() {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.ACCESS_NETWORK_STATE,
                "ConnectivityService");
    }

    private void enforceChangePermission() {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.CHANGE_NETWORK_STATE,
                "ConnectivityService");
    }

    // TODO Make this a special check when it goes public
    private void enforceTetherChangePermission() {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.CHANGE_NETWORK_STATE,
                "ConnectivityService");
    }

    private void enforceTetherAccessPermission() {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.ACCESS_NETWORK_STATE,
                "ConnectivityService");
    }

    private void enforceConnectivityInternalPermission() {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.CONNECTIVITY_INTERNAL,
                "ConnectivityService");
    }

    /**
     * Handle a {@code DISCONNECTED} event. If this pertains to the non-active
     * network, we ignore it. If it is for the active network, we send out a
     * broadcast. But first, we check whether it might be possible to connect
     * to a different network.
     * @param info the {@code NetworkInfo} for the network
     */
    private void handleDisconnect(NetworkInfo info) {

        int prevNetType = info.getType();

        mNetTrackers[prevNetType].setTeardownRequested(false);

        // Remove idletimer previously setup in {@code handleConnect}
        removeDataActivityTracking(prevNetType);

        /*
         * If the disconnected network is not the active one, then don't report
         * this as a loss of connectivity. What probably happened is that we're
         * getting the disconnect for a network that we explicitly disabled
         * in accordance with network preference policies.
         */
        if (!mNetConfigs[prevNetType].isDefault()) {
            List<Integer> pids = mNetRequestersPids[prevNetType];
            for (Integer pid : pids) {
                // will remove them because the net's no longer connected
                // need to do this now as only now do we know the pids and
                // can properly null things that are no longer referenced.
                reassessPidDns(pid.intValue(), false);
            }
        }

        Intent intent = new Intent(ConnectivityManager.CONNECTIVITY_ACTION);
        intent.putExtra(ConnectivityManager.EXTRA_NETWORK_INFO, new NetworkInfo(info));
        intent.putExtra(ConnectivityManager.EXTRA_NETWORK_TYPE, info.getType());
        if (info.isFailover()) {
            intent.putExtra(ConnectivityManager.EXTRA_IS_FAILOVER, true);
            info.setFailover(false);
        }
        if (info.getReason() != null) {
            intent.putExtra(ConnectivityManager.EXTRA_REASON, info.getReason());
        }
        if (info.getExtraInfo() != null) {
            intent.putExtra(ConnectivityManager.EXTRA_EXTRA_INFO,
                    info.getExtraInfo());
        }

        if (mNetConfigs[prevNetType].isDefault()) {
            tryFailover(prevNetType);
            if (mActiveDefaultNetwork != -1) {
                NetworkInfo switchTo = mNetTrackers[mActiveDefaultNetwork].getNetworkInfo();
                intent.putExtra(ConnectivityManager.EXTRA_OTHER_NETWORK_INFO, switchTo);
            } else {
                mDefaultInetConditionPublished = 0; // we're not connected anymore
                intent.putExtra(ConnectivityManager.EXTRA_NO_CONNECTIVITY, true);
            }
        }
        intent.putExtra(ConnectivityManager.EXTRA_INET_CONDITION, mDefaultInetConditionPublished);

        // Reset interface if no other connections are using the same interface
        boolean doReset = true;
        LinkProperties linkProperties = mNetTrackers[prevNetType].getLinkProperties();
        if (linkProperties != null) {
            String oldIface = linkProperties.getInterfaceName();
            if (TextUtils.isEmpty(oldIface) == false) {
                for (NetworkStateTracker networkStateTracker : mNetTrackers) {
                    if (networkStateTracker == null) continue;
                    NetworkInfo networkInfo = networkStateTracker.getNetworkInfo();
                    if (networkInfo.isConnected() && networkInfo.getType() != prevNetType) {
                        LinkProperties l = networkStateTracker.getLinkProperties();
                        if (l == null) continue;
                        if (oldIface.equals(l.getInterfaceName())) {
                            doReset = false;
                            break;
                        }
                    }
                }
            }
        }

        // do this before we broadcast the change
        handleConnectivityChange(prevNetType, doReset);

        final Intent immediateIntent = new Intent(intent);
        immediateIntent.setAction(CONNECTIVITY_ACTION_IMMEDIATE);
        sendStickyBroadcast(immediateIntent);
        sendStickyBroadcastDelayed(intent, getConnectivityChangeDelay());
        /*
         * If the failover network is already connected, then immediately send
         * out a followup broadcast indicating successful failover
         */
        if (mActiveDefaultNetwork != -1) {
            sendConnectedBroadcastDelayed(mNetTrackers[mActiveDefaultNetwork].getNetworkInfo(),
                    getConnectivityChangeDelay());
        }
    }

    private void tryFailover(int prevNetType) {
        /*
         * If this is a default network, check if other defaults are available.
         * Try to reconnect on all available and let them hash it out when
         * more than one connects.
         */
        if (mNetConfigs[prevNetType].isDefault()) {
            if (mActiveDefaultNetwork == prevNetType) {
                mActiveDefaultNetwork = -1;
            }

            // don't signal a reconnect for anything lower or equal priority than our
            // current connected default
            // TODO - don't filter by priority now - nice optimization but risky
//            int currentPriority = -1;
//            if (mActiveDefaultNetwork != -1) {
//                currentPriority = mNetConfigs[mActiveDefaultNetwork].mPriority;
//            }
            for (int checkType=0; checkType <= ConnectivityManager.MAX_NETWORK_TYPE; checkType++) {
                if (checkType == prevNetType) continue;
                if (mNetConfigs[checkType] == null) continue;
                if (!mNetConfigs[checkType].isDefault()) continue;
                if (mNetTrackers[checkType] == null) continue;

// Enabling the isAvailable() optimization caused mobile to not get
// selected if it was in the middle of error handling. Specifically
// a moble connection that took 30 seconds to complete the DEACTIVATE_DATA_CALL
// would not be available and we wouldn't get connected to anything.
// So removing the isAvailable() optimization below for now. TODO: This
// optimization should work and we need to investigate why it doesn't work.
// This could be related to how DEACTIVATE_DATA_CALL is reporting its
// complete before it is really complete.
//                if (!mNetTrackers[checkType].isAvailable()) continue;

//                if (currentPriority >= mNetConfigs[checkType].mPriority) continue;

                NetworkStateTracker checkTracker = mNetTrackers[checkType];
                NetworkInfo checkInfo = checkTracker.getNetworkInfo();
                if (!checkInfo.isConnectedOrConnecting() || checkTracker.isTeardownRequested()) {
                    checkInfo.setFailover(true);
                    checkTracker.reconnect();
                }
                if (DBG) log("Attempting to switch to " + checkInfo.getTypeName());
            }
        }
    }

    @Override
    public void sendConnectedBroadcast(NetworkInfo info) {
        enforceConnectivityInternalPermission();
        sendGeneralBroadcast(info, CONNECTIVITY_ACTION_IMMEDIATE);
        sendGeneralBroadcast(info, CONNECTIVITY_ACTION);
    }

    private void sendConnectedBroadcastDelayed(NetworkInfo info, int delayMs) {
        sendGeneralBroadcast(info, CONNECTIVITY_ACTION_IMMEDIATE);
        sendGeneralBroadcastDelayed(info, CONNECTIVITY_ACTION, delayMs);
    }

    private void sendInetConditionBroadcast(NetworkInfo info) {
        sendGeneralBroadcast(info, ConnectivityManager.INET_CONDITION_ACTION);
    }

    private Intent makeGeneralIntent(NetworkInfo info, String bcastType) {
        if (mLockdownTracker != null) {
            info = mLockdownTracker.augmentNetworkInfo(info);
        }

        Intent intent = new Intent(bcastType);
        intent.putExtra(ConnectivityManager.EXTRA_NETWORK_INFO, new NetworkInfo(info));
        intent.putExtra(ConnectivityManager.EXTRA_NETWORK_TYPE, info.getType());
        if (info.isFailover()) {
            intent.putExtra(ConnectivityManager.EXTRA_IS_FAILOVER, true);
            info.setFailover(false);
        }
        if (info.getReason() != null) {
            intent.putExtra(ConnectivityManager.EXTRA_REASON, info.getReason());
        }
        if (info.getExtraInfo() != null) {
            intent.putExtra(ConnectivityManager.EXTRA_EXTRA_INFO,
                    info.getExtraInfo());
        }
        intent.putExtra(ConnectivityManager.EXTRA_INET_CONDITION, mDefaultInetConditionPublished);
        return intent;
    }

    private void sendGeneralBroadcast(NetworkInfo info, String bcastType) {
        sendStickyBroadcast(makeGeneralIntent(info, bcastType));
    }

    private void sendGeneralBroadcastDelayed(NetworkInfo info, String bcastType, int delayMs) {
        sendStickyBroadcastDelayed(makeGeneralIntent(info, bcastType), delayMs);
    }

    private void sendDataActivityBroadcast(int deviceType, boolean active) {
        Intent intent = new Intent(ConnectivityManager.ACTION_DATA_ACTIVITY_CHANGE);
        intent.putExtra(ConnectivityManager.EXTRA_DEVICE_TYPE, deviceType);
        intent.putExtra(ConnectivityManager.EXTRA_IS_ACTIVE, active);
        final long ident = Binder.clearCallingIdentity();
        try {
            mContext.sendOrderedBroadcastAsUser(intent, UserHandle.ALL,
                    RECEIVE_DATA_ACTIVITY_CHANGE, null, null, 0, null, null);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    /**
     * Called when an attempt to fail over to another network has failed.
     * @param info the {@link NetworkInfo} for the failed network
     */
    private void handleConnectionFailure(NetworkInfo info) {
        mNetTrackers[info.getType()].setTeardownRequested(false);

        String reason = info.getReason();
        String extraInfo = info.getExtraInfo();

        String reasonText;
        if (reason == null) {
            reasonText = ".";
        } else {
            reasonText = " (" + reason + ").";
        }
        loge("Attempt to connect to " + info.getTypeName() + " failed" + reasonText);

        Intent intent = new Intent(ConnectivityManager.CONNECTIVITY_ACTION);
        intent.putExtra(ConnectivityManager.EXTRA_NETWORK_INFO, new NetworkInfo(info));
        intent.putExtra(ConnectivityManager.EXTRA_NETWORK_TYPE, info.getType());
        if (getActiveNetworkInfo() == null) {
            intent.putExtra(ConnectivityManager.EXTRA_NO_CONNECTIVITY, true);
        }
        if (reason != null) {
            intent.putExtra(ConnectivityManager.EXTRA_REASON, reason);
        }
        if (extraInfo != null) {
            intent.putExtra(ConnectivityManager.EXTRA_EXTRA_INFO, extraInfo);
        }
        if (info.isFailover()) {
            intent.putExtra(ConnectivityManager.EXTRA_IS_FAILOVER, true);
            info.setFailover(false);
        }

        if (mNetConfigs[info.getType()].isDefault()) {
            tryFailover(info.getType());
            if (mActiveDefaultNetwork != -1) {
                NetworkInfo switchTo = mNetTrackers[mActiveDefaultNetwork].getNetworkInfo();
                intent.putExtra(ConnectivityManager.EXTRA_OTHER_NETWORK_INFO, switchTo);
            } else {
                mDefaultInetConditionPublished = 0;
                intent.putExtra(ConnectivityManager.EXTRA_NO_CONNECTIVITY, true);
            }
        }

        intent.putExtra(ConnectivityManager.EXTRA_INET_CONDITION, mDefaultInetConditionPublished);

        final Intent immediateIntent = new Intent(intent);
        immediateIntent.setAction(CONNECTIVITY_ACTION_IMMEDIATE);
        sendStickyBroadcast(immediateIntent);
        sendStickyBroadcast(intent);
        /*
         * If the failover network is already connected, then immediately send
         * out a followup broadcast indicating successful failover
         */
        if (mActiveDefaultNetwork != -1) {
            sendConnectedBroadcast(mNetTrackers[mActiveDefaultNetwork].getNetworkInfo());
        }
    }

    private void sendStickyBroadcast(Intent intent) {
        synchronized(this) {
            if (!mSystemReady) {
                mInitialBroadcast = new Intent(intent);
            }
            intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
            if (VDBG) {
                log("sendStickyBroadcast: action=" + intent.getAction());
            }

            final long ident = Binder.clearCallingIdentity();
            try {
                mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    private void sendStickyBroadcastDelayed(Intent intent, int delayMs) {
        if (delayMs <= 0) {
            sendStickyBroadcast(intent);
        } else {
            if (VDBG) {
                log("sendStickyBroadcastDelayed: delayMs=" + delayMs + ", action="
                        + intent.getAction());
            }
            mHandler.sendMessageDelayed(mHandler.obtainMessage(
                    EVENT_SEND_STICKY_BROADCAST_INTENT, intent), delayMs);
        }
    }

    @Override
    public void systemReady() {
        synchronized(this) {
            mSystemReady = true;
            if (mInitialBroadcast != null) {
                mContext.sendStickyBroadcastAsUser(mInitialBroadcast, UserHandle.ALL);
                mInitialBroadcast = null;
            }
        }
        // load the global proxy at startup
        mHandler.sendMessage(mHandler.obtainMessage(EVENT_APPLY_GLOBAL_HTTP_PROXY));

        // Try bringing up tracker, but if KeyStore isn't ready yet, wait
        // for user to unlock device.
        if (!updateLockdownVpn()) {
            final IntentFilter filter = new IntentFilter(Intent.ACTION_USER_PRESENT);
            mContext.registerReceiver(mUserPresentReceiver, filter);
        }
    }

    private BroadcastReceiver mUserPresentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Try creating lockdown tracker, since user present usually means
            // unlocked keystore.
            if (updateLockdownVpn()) {
                mContext.unregisterReceiver(this);
            }
        }
    };

    private boolean isNewNetTypePreferredOverCurrentNetType(int type) {
        if ((type != mNetworkPreference &&
                    mNetConfigs[mActiveDefaultNetwork].priority >
                    mNetConfigs[type].priority) ||
                mNetworkPreference == mActiveDefaultNetwork) return false;
        return true;
    }

    private void handleConnect(NetworkInfo info) {
        final int newNetType = info.getType();

        setupDataActivityTracking(newNetType);

        // snapshot isFailover, because sendConnectedBroadcast() resets it
        boolean isFailover = info.isFailover();
        final NetworkStateTracker thisNet = mNetTrackers[newNetType];
        final String thisIface = thisNet.getLinkProperties().getInterfaceName();

        // if this is a default net and other default is running
        // kill the one not preferred
        if (mNetConfigs[newNetType].isDefault()) {
            if (mActiveDefaultNetwork != -1 && mActiveDefaultNetwork != newNetType) {
                if (isNewNetTypePreferredOverCurrentNetType(newNetType)) {
                    // tear down the other
                    NetworkStateTracker otherNet =
                            mNetTrackers[mActiveDefaultNetwork];
                    if (DBG) {
                        log("Policy requires " + otherNet.getNetworkInfo().getTypeName() +
                            " teardown");
                    }
                    if (!teardown(otherNet)) {
                        loge("Network declined teardown request");
                        teardown(thisNet);
                        return;
                    }
                } else {
                       // don't accept this one
                        if (VDBG) {
                            log("Not broadcasting CONNECT_ACTION " +
                                "to torn down network " + info.getTypeName());
                        }
                        teardown(thisNet);
                        return;
                }
            }
            synchronized (QcConnectivityService.this) {
                // have a new default network, release the transition wakelock in a second
                // if it's held.  The second pause is to allow apps to reconnect over the
                // new network
                if (mNetTransitionWakeLock.isHeld()) {
                    mHandler.sendMessageDelayed(mHandler.obtainMessage(
                            EVENT_CLEAR_NET_TRANSITION_WAKELOCK,
                            mNetTransitionWakeLockSerialNumber, 0),
                            1000);
                }
            }
            mActiveDefaultNetwork = newNetType;
            // this will cause us to come up initially as unconnected and switching
            // to connected after our normal pause unless somebody reports us as reall
            // disconnected
            mDefaultInetConditionPublished = 0;
            mDefaultConnectionSequence++;
            mInetConditionChangeInFlight = false;
            // Don't do this - if we never sign in stay, grey
            //reportNetworkCondition(mActiveDefaultNetwork, 100);
        }
        thisNet.setTeardownRequested(false);
        updateNetworkSettings(thisNet);
        handleConnectivityChange(newNetType, false);
        sendConnectedBroadcastDelayed(info, getConnectivityChangeDelay());

        // notify battery stats service about this network
        if (thisIface != null) {
            try {
                BatteryStatsService.getService().noteNetworkInterfaceType(thisIface, newNetType);
            } catch (RemoteException e) {
                // ignored; service lives in system_server
            }
        }
    }

    private void handleCaptivePortalTrackerCheck(NetworkInfo info) {
        if (DBG) log("Captive portal check " + info);
        int type = info.getType();
        final NetworkStateTracker thisNet = mNetTrackers[type];
        if (mNetConfigs[type].isDefault()) {
            if (mActiveDefaultNetwork != -1 && mActiveDefaultNetwork != type) {
                if (isNewNetTypePreferredOverCurrentNetType(type)) {
                    if (DBG) log("Captive check on " + info.getTypeName());
                    mCaptivePortalTracker.detectCaptivePortal(new NetworkInfo(info));
                    return;
                } else {
                    if (DBG) log("Tear down low priority net " + info.getTypeName());
                    teardown(thisNet);
                    return;
                }
            }
        }

        thisNet.captivePortalCheckComplete();
    }

    /** @hide */
    @Override
    public void captivePortalCheckComplete(NetworkInfo info) {
        enforceConnectivityInternalPermission();
        mNetTrackers[info.getType()].captivePortalCheckComplete();
    }

    /**
     * Setup data activity tracking for the given network interface.
     *
     * Every {@code setupDataActivityTracking} should be paired with a
     * {@link removeDataActivityTracking} for cleanup.
     */
    private void setupDataActivityTracking(int type) {
        final NetworkStateTracker thisNet = mNetTrackers[type];
        final String iface = thisNet.getLinkProperties().getInterfaceName();

        final int timeout;

        if (ConnectivityManager.isNetworkTypeMobile(type)) {
            timeout = Settings.Global.getInt(mContext.getContentResolver(),
                                             Settings.Global.DATA_ACTIVITY_TIMEOUT_MOBILE,
                                             0);
            // Canonicalize mobile network type
            type = ConnectivityManager.TYPE_MOBILE;
        } else if (ConnectivityManager.TYPE_WIFI == type) {
            timeout = Settings.Global.getInt(mContext.getContentResolver(),
                                             Settings.Global.DATA_ACTIVITY_TIMEOUT_WIFI,
                                             0);
        } else {
            // do not track any other networks
            timeout = 0;
        }

        if (timeout > 0 && iface != null) {
            try {
                mNetd.addIdleTimer(iface, timeout, Integer.toString(type));
            } catch (RemoteException e) {
            }
        }
    }

    /**
     * Remove data activity tracking when network disconnects.
     */
    private void removeDataActivityTracking(int type) {
        final NetworkStateTracker net = mNetTrackers[type];
        final String iface = net.getLinkProperties().getInterfaceName();

        if (iface != null && (ConnectivityManager.isNetworkTypeMobile(type) ||
                              ConnectivityManager.TYPE_WIFI == type)) {
            try {
                // the call fails silently if no idletimer setup for this interface
                mNetd.removeIdleTimer(iface);
            } catch (RemoteException e) {
            }
        }
    }

    /**
     * After a change in the connectivity state of a network. We're mainly
     * concerned with making sure that the list of DNS servers is set up
     * according to which networks are connected, and ensuring that the
     * right routing table entries exist.
     */
    private void handleConnectivityChange(int netType, boolean doReset) {
        int resetMask = doReset ? NetworkUtils.RESET_ALL_ADDRESSES : 0;

        /*
         * If a non-default network is enabled, add the host routes that
         * will allow it's DNS servers to be accessed.
         */
        handleDnsConfigurationChange(netType);

        LinkProperties curLp = mCurrentLinkProperties[netType];
        LinkProperties newLp = null;

        if (mNetTrackers[netType].getNetworkInfo().isConnected()) {
            newLp = mNetTrackers[netType].getLinkProperties();
            if (VDBG) {
                log("handleConnectivityChange: changed linkProperty[" + netType + "]:" +
                        " doReset=" + doReset + " resetMask=" + resetMask +
                        "\n   curLp=" + curLp +
                        "\n   newLp=" + newLp);
            }

            if (curLp != null) {
                if (curLp.isIdenticalInterfaceName(newLp)) {
                    CompareResult<LinkAddress> car = curLp.compareAddresses(newLp);
                    if ((car.removed.size() != 0) || (car.added.size() != 0)) {
                        for (LinkAddress linkAddr : car.removed) {
                            if (linkAddr.getAddress() instanceof Inet4Address) {
                                resetMask |= NetworkUtils.RESET_IPV4_ADDRESSES;
                            }
                            if (linkAddr.getAddress() instanceof Inet6Address) {
                                resetMask |= NetworkUtils.RESET_IPV6_ADDRESSES;
                            }
                        }
                        if (DBG) {
                            log("handleConnectivityChange: addresses changed" +
                                    " linkProperty[" + netType + "]:" + " resetMask=" + resetMask +
                                    "\n   car=" + car);
                        }
                    } else {
                        if (DBG) {
                            log("handleConnectivityChange: address are the same reset per doReset" +
                                   " linkProperty[" + netType + "]:" +
                                   " resetMask=" + resetMask);
                        }
                    }
                } else {
                    resetMask = NetworkUtils.RESET_ALL_ADDRESSES;
                    if (DBG) {
                        log("handleConnectivityChange: interface not not equivalent reset both" +
                                " linkProperty[" + netType + "]:" +
                                " resetMask=" + resetMask);
                    }
                }
            }
            if (mNetConfigs[netType].isDefault()) {
                handleApplyDefaultProxy(newLp.getHttpProxy());
            }
        } else {
            if (VDBG) {
                log("handleConnectivityChange: changed linkProperty[" + netType + "]:" +
                        " doReset=" + doReset + " resetMask=" + resetMask +
                        "\n  curLp=" + curLp +
                        "\n  newLp= null");
            }
        }
        mCurrentLinkProperties[netType] = newLp;
            boolean resetDns = updateRoutes( newLp,
                                             curLp,
                                             mNetConfigs[netType].isDefault(),
                                             mRouteAttributes[netType] );

        if (resetMask != 0 || resetDns) {
            if (curLp != null) {
                for (String iface : curLp.getAllInterfaceNames()) {
                    if (TextUtils.isEmpty(iface) == false) {
                        if (resetMask != 0) {
                            if (DBG) log("resetConnections(" + iface + ", " + resetMask + ")");
                            NetworkUtils.resetConnections(iface, resetMask);

                            // Tell VPN the interface is down. It is a temporary
                            // but effective fix to make VPN aware of the change.
                            if ((resetMask & NetworkUtils.RESET_IPV4_ADDRESSES) != 0) {
                                mVpn.interfaceStatusChanged(iface, false);
                            }
                        }
                        if (resetDns) {
                            flushVmDnsCache();
                            if (VDBG) log("resetting DNS cache for " + iface);
                            try {
                                mNetd.flushInterfaceDnsCache(iface);
                            } catch (Exception e) {
                                // never crash - catch them all
                                if (DBG) loge("Exception resetting dns cache: " + e);
                            }
                        }
                    } else {
                        loge("Can't reset connection for type "+netType);
                    }
                }
            }
        }

        // Update 464xlat state.
        NetworkStateTracker tracker = mNetTrackers[netType];
        if (mClat.requiresClat(netType, tracker)) {
            // If the connection was previously using clat, but is not using it now, stop the clat
            // daemon. Normally, this happens automatically when the connection disconnects, but if
            // the disconnect is not reported, or if the connection's LinkProperties changed for
            // some other reason (e.g., handoff changes the IP addresses on the link), it would
            // still be running. If it's not running, then stopping it is a no-op.
            if (Nat464Xlat.isRunningClat(curLp) && !Nat464Xlat.isRunningClat(newLp)) {
                mClat.stopClat();
            }
            // If the link requires clat to be running, then start the daemon now.
            if (mNetTrackers[netType].getNetworkInfo().isConnected()) {
                mClat.startClat(tracker);
            } else {
                mClat.stopClat();
            }
        }

        // TODO: Temporary notifying upstread change to Tethering.
        //       @see bug/4455071
        /** Notify TetheringService if interface name has been changed. */
        if (TextUtils.equals(mNetTrackers[netType].getNetworkInfo().getReason(),
                             PhoneConstants.REASON_LINK_PROPERTIES_CHANGED)) {
            if (isTetheringSupported()) {
                mTethering.handleTetherIfaceChange();
            }
        }
    }

    /**
     * Add and remove routes using the old properties (null if not previously connected),
     * new properties (null if becoming disconnected).  May even be double null, which
     * is a noop.
     * Uses isLinkDefault to determine if default routes should be set. Adds host routes
     * to the dns servers for all networks. Adds source policy routes for all networks.
     * Returns a boolean indicating the routes changed
     */
    private boolean updateRoutes(LinkProperties newLp, LinkProperties curLp,
                boolean isLinkDefault, RouteAttributes ra) {
        Collection<RouteInfo> routesToAdd = null;
        CompareResult<InetAddress> dnsDiff = new CompareResult<InetAddress>();
        CompareResult<RouteInfo> routeDiff = new CompareResult<RouteInfo>();
        CompareResult<LinkAddress> localAddrDiff = new CompareResult<LinkAddress>();
        if (curLp != null) {
            // check for the delta between the current set and the new
            routeDiff = curLp.compareRoutes(newLp);
            dnsDiff = curLp.compareDnses(newLp);
                localAddrDiff = curLp.compareAddresses(newLp);
        } else if (newLp != null) {
            routeDiff.added = newLp.getAllRoutes();
            dnsDiff.added = newLp.getDnses();
            localAddrDiff.added = newLp.getLinkAddresses();
        }

        boolean routesChanged = (routeDiff.removed.size() != 0 || routeDiff.added.size() != 0);

        for (RouteInfo r : routeDiff.removed) {
            if (isLinkDefault || ! r.isDefaultRoute()) {
                removeRoute(curLp, r, TO_DEFAULT_TABLE);
            }
            if (isLinkDefault == false) {
                // remove from a secondary route table
                removeRoute(curLp, r, TO_SECONDARY_TABLE);
            }
        }

        // handle DNS routes for all net types - no harm done
        if (routesChanged) {
            // routes changed - remove all old dns entries and add new
            if (curLp != null) {
                for (InetAddress oldDns : curLp.getDnses()) {
                    removeRouteToAddress(curLp, oldDns);
                }
            }
            if (newLp != null) {
                for (InetAddress newDns : newLp.getDnses()) {
                    addRouteToAddress(newLp, newDns);
                }
            }
        } else {
            // no change in routes, check for change in dns themselves
            for (InetAddress oldDns : dnsDiff.removed) {
                removeRouteToAddress(curLp, oldDns);
            }
            for (InetAddress newDns : dnsDiff.added) {
                addRouteToAddress(newLp, newDns);
            }
        }

        for (RouteInfo r :  routeDiff.added) {
            if (isLinkDefault || ! r.isDefaultRoute()) {
                addRoute(newLp, r, TO_DEFAULT_TABLE);
            } else {
                // add to a secondary route table
                addRoute(newLp, r, TO_SECONDARY_TABLE);

                // many radios add a default route even when we don't want one.
                // remove the default route unless somebody else has asked for it
                String ifaceName = newLp.getInterfaceName();
                if (TextUtils.isEmpty(ifaceName) == false && mAddedRoutes.contains(r) == false) {
                    if (VDBG) log("Removing " + r + " for interface " + ifaceName);
                    try {
                        mNetd.removeRoute(ifaceName, r);
                    } catch (Exception e) {
                        // never crash - catch them all
                        if (DBG) loge("Exception trying to remove a route: " + e);
                    }
                }
            }
        }

        if (localAddrDiff.removed.size() != 0) {
            for (LinkAddress la : localAddrDiff.removed) {
                if (VDBG) log("Removing src route for:" + la.getAddress().getHostAddress());
                try {
                     mNetd.delSrcRoute(la.getAddress().getAddress(), ra.getTableId());
                } catch (Exception e) {
                    loge("Exception while trying to remove src route: " + e);
                }
            }
        }

        if (localAddrDiff.added.size() != 0) {
            InetAddress gw4Addr = null, gw6Addr = null;
            String ifaceName = newLp.getInterfaceName();
            if (! TextUtils.isEmpty(ifaceName)) {
                for (RouteInfo r : newLp.getRoutes()) {
                    if (! r.isDefaultRoute()) continue;
                    if (r.getGateway() instanceof Inet4Address)
                        gw4Addr = r.getGateway();
                    else
                        gw6Addr = r.getGateway();
                } //gateway is optional so continue adding the source route.
                for (LinkAddress la : localAddrDiff.added) {
                    try {
                        if (la.getAddress() instanceof Inet4Address) {
                            mNetd.replaceSrcRoute(ifaceName, la.getAddress().getAddress(),
                                    gw4Addr.getAddress(), ra.getTableId());
                        } else {
                            mNetd.replaceSrcRoute(ifaceName, la.getAddress().getAddress(),
                                    gw6Addr.getAddress(), ra.getTableId());
                        }
                    } catch (Exception e) {
                        //never crash, catch them all
                        loge("Exception while trying to add src route: " + e);
                    }
                }
            }
        }
        return routesChanged;
    }


   /**
     * Reads the network specific TCP buffer sizes from SystemProperties
     * net.tcp.buffersize.[default|wifi|umts|edge|gprs] and set them for system
     * wide use
     */
   private void updateNetworkSettings(NetworkStateTracker nt) {
        String key = nt.getTcpBufferSizesPropName();
        String bufferSizes = key == null ? null : SystemProperties.get(key);

        if (TextUtils.isEmpty(bufferSizes)) {
            if (VDBG) log(key + " not found in system properties. Using defaults");

            // Setting to default values so we won't be stuck to previous values
            key = "net.tcp.buffersize.default";
            bufferSizes = SystemProperties.get(key);
        }

        // Set values in kernel
        if (bufferSizes.length() != 0) {
            if (VDBG) {
                log("Setting TCP values: [" + bufferSizes
                        + "] which comes from [" + key + "]");
            }
            setBufferSize(bufferSizes);
        }
    }

   /**
     * Writes TCP buffer sizes to /sys/kernel/ipv4/tcp_[r/w]mem_[min/def/max]
     * which maps to /proc/sys/net/ipv4/tcp_rmem and tcpwmem
     *
     * @param bufferSizes in the format of "readMin, readInitial, readMax,
     *        writeMin, writeInitial, writeMax"
     */
    private void setBufferSize(String bufferSizes) {
        try {
            String[] values = bufferSizes.split(",");

            if (values.length == 6) {
              final String prefix = "/sys/kernel/ipv4/tcp_";
                FileUtils.stringToFile(prefix + "rmem_min", values[0]);
                FileUtils.stringToFile(prefix + "rmem_def", values[1]);
                FileUtils.stringToFile(prefix + "rmem_max", values[2]);
                FileUtils.stringToFile(prefix + "wmem_min", values[3]);
                FileUtils.stringToFile(prefix + "wmem_def", values[4]);
                FileUtils.stringToFile(prefix + "wmem_max", values[5]);
            } else {
                loge("Invalid buffersize string: " + bufferSizes);
            }
        } catch (IOException e) {
            loge("Can't set tcp buffer sizes:" + e);
        }
    }

    /**
     * Adjust the per-process dns entries (net.dns<x>.<pid>) based
     * on the highest priority active net which this process requested.
     * If there aren't any, clear it out
     */
    private void reassessPidDns(int pid, boolean doBump)
    {
        if (VDBG) log("reassessPidDns for pid " + pid);
        Integer myPid = new Integer(pid);
        for(int i : mPriorityList) {
            if (mNetConfigs[i].isDefault()) {
                continue;
            }
            NetworkStateTracker nt = mNetTrackers[i];
            if (nt.getNetworkInfo().isConnected() &&
                    !nt.isTeardownRequested()) {
                LinkProperties p = nt.getLinkProperties();
                if (p == null) continue;
                if (mNetRequestersPids[i].contains(myPid)) {
                    try {
                        mNetd.setDnsInterfaceForPid(p.getInterfaceName(), pid);
                    } catch (Exception e) {
                        Slog.e(TAG, "exception reasseses pid dns: " + e);
                    }
                    return;
                }
           }
        }
        // nothing found - delete
        try {
            mNetd.clearDnsInterfaceForPid(pid);
        } catch (Exception e) {
            Slog.e(TAG, "exception clear interface from pid: " + e);
        }
    }

    private void flushVmDnsCache() {
        /*
         * Tell the VMs to toss their DNS caches
         */
        Intent intent = new Intent(Intent.ACTION_CLEAR_DNS_CACHE);
        intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
        /*
         * Connectivity events can happen before boot has completed ...
         */
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        final long ident = Binder.clearCallingIdentity();
        try {
            mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    // Caller must grab mDnsLock.
    private void updateDnsLocked(String network, String iface,
            Collection<InetAddress> dnses, String domains) {
        int last = 0;
        if (dnses.size() == 0 && mDefaultDns != null) {
            dnses = new ArrayList();
            dnses.add(mDefaultDns);
            if (DBG) {
                loge("no dns provided for " + network + " - using " + mDefaultDns.getHostAddress());
            }
        }

        try {
            mNetd.setDnsServersForInterface(iface, NetworkUtils.makeStrings(dnses), domains);
            mNetd.setDefaultInterfaceForDns(iface);
            for (InetAddress dns : dnses) {
                ++last;
                String key = "net.dns" + last;
                String value = dns.getHostAddress();
                SystemProperties.set(key, value);
            }
            for (int i = last + 1; i <= mNumDnsEntries; ++i) {
                String key = "net.dns" + i;
                SystemProperties.set(key, "");
            }
            mNumDnsEntries = last;
        } catch (Exception e) {
            if (DBG) loge("exception setting default dns interface: " + e);
        }
    }

    private void handleDnsConfigurationChange(int netType) {
        // add default net's dns entries
        NetworkStateTracker nt = mNetTrackers[netType];
        if (nt != null && nt.getNetworkInfo().isConnected() && !nt.isTeardownRequested()) {
            LinkProperties p = nt.getLinkProperties();
            if (p == null) return;
            Collection<InetAddress> dnses = p.getDnses();
            if (mNetConfigs[netType].isDefault()) {
                String network = nt.getNetworkInfo().getTypeName();
                synchronized (mDnsLock) {
                    if (!mDnsOverridden) {
                        updateDnsLocked(network, p.getInterfaceName(), dnses, p.getDomains());
                    }
                }
            } else {
                try {
                    mNetd.setDnsServersForInterface(p.getInterfaceName(),
                            NetworkUtils.makeStrings(dnses), p.getDomains());
                } catch (Exception e) {
                    if (DBG) loge("exception setting dns servers: " + e);
                }
                // set per-pid dns for attached secondary nets
                List<Integer> pids = mNetRequestersPids[netType];
                for (Integer pid : pids) {
                    try {
                        mNetd.setDnsInterfaceForPid(p.getInterfaceName(), pid);
                    } catch (Exception e) {
                        Slog.e(TAG, "exception setting interface for pid: " + e);
                    }
                }
            }
            flushVmDnsCache();
        }
    }

    private int getRestoreDefaultNetworkDelay(int networkType) {
        String restoreDefaultNetworkDelayStr = SystemProperties.get(
                NETWORK_RESTORE_DELAY_PROP_NAME);
        if(restoreDefaultNetworkDelayStr != null &&
                restoreDefaultNetworkDelayStr.length() != 0) {
            try {
                return Integer.valueOf(restoreDefaultNetworkDelayStr);
            } catch (NumberFormatException e) {
            }
        }
        // if the system property isn't set, use the value for the apn type
        int ret = RESTORE_DEFAULT_NETWORK_DELAY;

        if ((networkType <= ConnectivityManager.MAX_NETWORK_TYPE) &&
                (mNetConfigs[networkType] != null)) {
            ret = mNetConfigs[networkType].restoreTime;
        }
        return ret;
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        final IndentingPrintWriter pw = new IndentingPrintWriter(writer, "  ");
        if (mContext.checkCallingOrSelfPermission(
                android.Manifest.permission.DUMP)
                != PackageManager.PERMISSION_GRANTED) {
            pw.println("Permission Denial: can't dump ConnectivityService " +
                    "from from pid=" + Binder.getCallingPid() + ", uid=" +
                    Binder.getCallingUid());
            return;
        }

        // TODO: add locking to get atomic snapshot
        pw.println();
        for (int i = 0; i < mNetTrackers.length; i++) {
            final NetworkStateTracker nst = mNetTrackers[i];
            if (nst != null) {
                pw.println("NetworkStateTracker for " + getNetworkTypeName(i) + ":");
                pw.increaseIndent();
                if (nst.getNetworkInfo().isConnected()) {
                    pw.println("Active network: " + nst.getNetworkInfo().
                            getTypeName());
                }
                pw.println(nst.getNetworkInfo());
                pw.println(nst.getLinkProperties());
                pw.println(nst);
                pw.println();
                pw.decreaseIndent();
            }
        }

        pw.println("Network Requester Pids:");
        pw.increaseIndent();
        for (int net : mPriorityList) {
            String pidString = net + ": ";
            for (Integer pid : mNetRequestersPids[net]) {
                pidString = pidString + pid.toString() + ", ";
            }
            pw.println(pidString);
        }
        pw.println();
        pw.decreaseIndent();

        pw.println("FeatureUsers:");
        pw.increaseIndent();
        for (Object requester : mFeatureUsers) {
            pw.println(requester.toString());
        }
        pw.println();
        pw.decreaseIndent();

        synchronized (this) {
            pw.println("NetworkTranstionWakeLock is currently " +
                    (mNetTransitionWakeLock.isHeld() ? "" : "not ") + "held.");
            pw.println("It was last requested for "+mNetTransitionWakeLockCausedBy);
        }
        pw.println();

        mTethering.dump(fd, pw, args);

        if (mInetLog != null) {
            pw.println();
            pw.println("Inet condition reports:");
            pw.increaseIndent();
            for(int i = 0; i < mInetLog.size(); i++) {
                pw.println(mInetLog.get(i));
            }
            pw.decreaseIndent();
        }
    }

    private final class ConnectivityServiceHSM extends StateMachine {

        // min HSM internal message
        static final int HSM_MSG_MIN = 5000;

        // handleConnect
        static final int HSM_HANDLE_CONNECT = HSM_MSG_MIN + 1;
        // handleDisconnect
        static final int HSM_HANDLE_DISCONNECT = HSM_MSG_MIN + 2;
        // handleConnecitivtyChange
        static final int HSM_HANDLE_CONNECTIVITY_CHANGE = HSM_MSG_MIN + 3;
        // handleDnsConfigurationChange
        static final int HSM_HANDLE_DNS_CONFIGURATION_CHANGE = HSM_MSG_MIN + 4;
        // handleConnectionFailure
        static final int HSM_HANDLE_CONNECTION_FAILURE = HSM_MSG_MIN + 5;
        // handleInetConditionChange
        static final int HSM_HANDLE_INET_CONDITION_CHANGE = HSM_MSG_MIN + 6;
        // handleInetConditionHoldEnd
        static final int HSM_HANDLE_INET_CONDITION_HOLD_END = HSM_MSG_MIN + 7;
        // enforcePreference
        static final int HSM_EVENT_ENFORCE_PREFERENCE = HSM_MSG_MIN + 8;
        // restoredns
        static final int HSM_EVENT_RESTORE_DNS = HSM_MSG_MIN + 9;
        // handlecaptiveportalcheck
        static final int HSM_HANDLE_CAPTIVE_PORTAL_CHECK = HSM_MSG_MIN + 10;
        // handleRequestNetworkTransitionWakelock
        static final int HSM_HANDLE_REQUEST_NET_TRANSITION_WAKELOCK = HSM_MSG_MIN + 11;

        private int myDefaultDnsNet;
        // List to track multiple active default networks
        private ConnectedDefaultNetworkSet mConnectedDefaultNetworks;
        //Maximum simultaneous default networks supported in normal operation.

        public void sendMessageImmediate (Message msg) {
            sendMessageAtFrontOfQueue(msg);
        }

        private void sendConnectivityUpBroadcast(int type) {
            Intent intent = new Intent(CONNECTIVITY_AVAILABLE);
            intent.putExtra(EXTRA_NETWORK_TYPE, type);
            try {
                mContext.sendBroadcast(intent);
            } catch (SecurityException se) {
                loge("sendPrefChangedBroadcast() SecurityException: " + se);
            }
        }


        /**
         * Class that tracks active default networks.
         * Allows simultaneous Mobile and Wifi default networks
         */
        private class ConnectedDefaultNetworkSet {

            private final int MAX_SIMULTANEOUS_DEFAULTS = 2;
            private Collection<Integer> mDefaultNetworks;

            // Empty constructor
            public ConnectedDefaultNetworkSet() {
                mDefaultNetworks = new HashSet<Integer>(2);
            }

            public int size() {
                return mDefaultNetworks.size();
            }

            public boolean add(int i) {
                Integer j = new Integer(i);
                // restrict size to max simultaneous default networks
                if (mDefaultNetworks.size() >= MAX_SIMULTANEOUS_DEFAULTS) return false;
                // Allow only wifi and mobile for simultaneous connection
                if ((i != TYPE_WIFI) && (i != TYPE_MOBILE)) return false;
                if (mDefaultNetworks.contains(j)) return true;
                return mDefaultNetworks.add(j);
            }

            public boolean remove(int i) {
                return mDefaultNetworks.remove(new Integer(i));
            }

            public boolean contains(int i) {
                return mDefaultNetworks.contains(new Integer(i));
            }

            public boolean isHigherPriorityNetwork(int i) {
                int res = 0;
                if (mDefaultNetworks.isEmpty()) return true;
                for (Integer type : mDefaultNetworks) {
                    res += (mNetConfigs[i].priority > mNetConfigs[type.intValue()].priority) ? 1:0;
                }
                return ((res > 0) ? (res == mDefaultNetworks.size()) : false);
            }

            public Collection<Integer> getActiveDefaults() {
                return Collections.unmodifiableCollection(mDefaultNetworks);
            }

            public void clear() {
                mDefaultNetworks.clear();
            }

        }

        private State mDefaultConnectivityState;
        private SmartConnectivityState  mSmartConnectivityState;
        private DualConnectivityState mWifiDefaultState;
        private DualConnectivityState mMobileDefaultState;
        private State myInitialState;

        /**
         *
         * STATE MAP
         *                     DefaultConnectivityState
         *                                |
         *                     SmartConnectivityState
         *                            /             \
         *                           /               \
         *  Dual connectivity:  WifiDefaultState --  WwanDefaultState
         *
         */

        ConnectivityServiceHSM(Context context, String name, Looper looper) {
            super(name, looper);

            mConnectedDefaultNetworks = new ConnectedDefaultNetworkSet();

            mDefaultConnectivityState = new DefaultConnectivityState();
            addState(mDefaultConnectivityState);

            if (wqeEnabled) {
                // cne dual net mode enabled;
                mSmartConnectivityState = new SmartConnectivityState();
                addState(mSmartConnectivityState, mDefaultConnectivityState);
                mWifiDefaultState = new WifiDefaultState();
                addState(mWifiDefaultState, mSmartConnectivityState);
                mMobileDefaultState = new MobileDefaultState();
                addState(mMobileDefaultState, mSmartConnectivityState);
                myInitialState = mSmartConnectivityState;
            }  else {
                // cne single net, cne disabled mode device.
                myInitialState = mDefaultConnectivityState;
            }

            setInitialState(myInitialState);
        }

        private final class DefaultConnectivityState extends State {
            public DefaultConnectivityState() {
            }

            @Override
            public void enter() {
                if (DBG) log( "ConnectivityServiceHSM entering " + getCurrentState().getName());
            }

            @Override
            public void exit() {
                if (DBG) log( "ConnectivityServiceHSM leaving " + getCurrentState().getName());
        }

        @Override
        public boolean processMessage(Message msg) {
                if (DBG) log("Actual State: DefaultConnectivityState, Current State: " +
                        getCurrentState().getName() + ".processMessage what=" + msg.what);

            NetworkInfo info;
            switch (msg.what) {
                case NetworkStateTracker.EVENT_STATE_CHANGED:
                {
                    info = (NetworkInfo) msg.obj;
                    int type = info.getType();
                    NetworkInfo.State state = info.getState();

                    if (VDBG || (state == NetworkInfo.State.CONNECTED) ||
                            (state == NetworkInfo.State.DISCONNECTED)) {
                        log("ConnectivityChange for " +
                            info.getTypeName() + ": " +
                            state + "/" + info.getDetailedState());
                    }

                    EventLogTags.writeConnectivityStateChanged(
                            info.getType(), info.getSubtype(), info.getDetailedState().ordinal());

                    if (info.getDetailedState() ==
                            NetworkInfo.DetailedState.FAILED) {
                        sendMessageAtFrontOfQueue(HSM_HANDLE_CONNECTION_FAILURE, info);
                    } else if (info.getDetailedState() ==
                            DetailedState.CAPTIVE_PORTAL_CHECK) {
                        sendMessageAtFrontOfQueue(HSM_HANDLE_CAPTIVE_PORTAL_CHECK, info);
                    } else if (state == NetworkInfo.State.DISCONNECTED) {
                        sendMessageAtFrontOfQueue(HSM_HANDLE_DISCONNECT, info);
                    } else if (state == NetworkInfo.State.SUSPENDED) {
                        // TODO: need to think this over.
                        // the logic here is, handle SUSPENDED the same as
                        // DISCONNECTED. The only difference being we are
                        // broadcasting an intent with NetworkInfo that's
                        // suspended. This allows the applications an
                        // opportunity to handle DISCONNECTED and SUSPENDED
                        // differently, or not.
                        sendMessageAtFrontOfQueue(HSM_HANDLE_DISCONNECT, info);
                    } else if (state == NetworkInfo.State.CONNECTED) {
                        sendMessageAtFrontOfQueue(HSM_HANDLE_CONNECT, info);
                    }
                    if (mLockdownTracker != null) {
                        mLockdownTracker.onNetworkInfoChanged(info);
                    }
                    break;
                    }
                case NetworkStateTracker.EVENT_CONFIGURATION_CHANGED:
                {
                    info = (NetworkInfo) msg.obj;
                    // TODO: Temporary allowing network configuration
                    //       change not resetting sockets.
                    //       @see bug/4455071
                    sendMessageAtFrontOfQueue(obtainMessage(
                                HSM_HANDLE_CONNECTIVITY_CHANGE,
                                info.getType(), 0));
                    break;
                }
                case NetworkStateTracker.EVENT_NETWORK_SUBTYPE_CHANGED:
                {
                    info = (NetworkInfo) msg.obj;
                    updateNetworkSettings(mNetTrackers[info.getType()]);
                    break;
                }
                case EVENT_CLEAR_NET_TRANSITION_WAKELOCK:
                {
                    String causedBy = null;
                        synchronized (QcConnectivityService.this) {
                        if (msg.arg1 == mNetTransitionWakeLockSerialNumber &&
                                mNetTransitionWakeLock.isHeld()) {
                            mNetTransitionWakeLock.release();
                            causedBy = mNetTransitionWakeLockCausedBy;
                        }
                    }
                    if (causedBy != null) {
                        log("NetTransition Wakelock for " + causedBy + " released by timeout");
                    }
                    break;
                }
                case EVENT_RESTORE_DEFAULT_NETWORK:
                {
                    FeatureUser u = (FeatureUser)msg.obj;
                    u.expire();
                    break;
                }
                case EVENT_INET_CONDITION_CHANGE:
                {
                    sendMessageAtFrontOfQueue(obtainMessage(
                                HSM_HANDLE_INET_CONDITION_CHANGE, msg.arg1, msg.arg2));
                    break;
                }
                case EVENT_INET_CONDITION_HOLD_END:
                {
                    sendMessageAtFrontOfQueue(obtainMessage(
                            HSM_HANDLE_INET_CONDITION_HOLD_END, msg.arg1, msg.arg2));
                    break;
                }
                case EVENT_SET_NETWORK_PREFERENCE:
                {
                    int preference = msg.arg1;
                    handleSetNetworkPreference(preference);
                    break;
                }
                case EVENT_SET_MOBILE_DATA:
                {
                    boolean enabled = (msg.arg1 == ENABLED);
                    handleSetMobileData(enabled);
                    break;
                }
                case EVENT_APPLY_GLOBAL_HTTP_PROXY:
                {
                    handleDeprecatedGlobalHttpProxy();
                    break;
                }
                case EVENT_SET_DEPENDENCY_MET:
                {
                    boolean met = (msg.arg1 == ENABLED);
                    handleSetDependencyMet(msg.arg2, met);
                    break;
                }
                case EVENT_RESTORE_DNS:
                {
                    sendMessageAtFrontOfQueue(HSM_EVENT_RESTORE_DNS);
                    break;
                }
                case EVENT_SEND_STICKY_BROADCAST_INTENT:
                {
                    Intent intent = (Intent)msg.obj;
                    sendStickyBroadcast(intent);
                    break;
                }
                case EVENT_SET_POLICY_DATA_ENABLE:
                {
                    final int networkType = msg.arg1;
                    final boolean enabled = msg.arg2 == ENABLED;
                    handleSetPolicyDataEnable(networkType, enabled);
                    break;
                }
                case EVENT_VPN_STATE_CHANGED:
                {
                    if (mLockdownTracker != null) {
                        mLockdownTracker.onVpnStateChanged((NetworkInfo) msg.obj);
                    }
                    break;
                }
                    case EVENT_UPDATE_BLOCKED_UID:
                    {
                        handleUpdateBlockedUids(msg.arg1, (msg.arg2 == 1));
                        break;
                    }


                    /**
                     * Default connectivity service event handler implementation used
                     * by the default connectivity state
                     */
                    case HSM_HANDLE_CONNECT:
                        info = (NetworkInfo) msg.obj;
                        handleConnect(info);
                        break;
                    case HSM_HANDLE_DISCONNECT:
                        info = (NetworkInfo) msg.obj;
                        handleDisconnect(info);
                        break;
                    case HSM_HANDLE_CONNECTIVITY_CHANGE:
                    {
                        int type = msg.arg1;
                        boolean doReset = (msg.arg2 == 1);
                        handleConnectivityChange(type, doReset);
                        break;
                    }
                    case HSM_HANDLE_DNS_CONFIGURATION_CHANGE:
                        handleDnsConfigurationChange(msg.arg1);
                        break;
                    case HSM_HANDLE_CONNECTION_FAILURE:
                        info = (NetworkInfo) msg.obj;
                        handleConnectionFailure(info);
                        break;
                    case HSM_HANDLE_CAPTIVE_PORTAL_CHECK:
                         info = (NetworkInfo) msg.obj;
                         QcConnectivityService.this.handleCaptivePortalTrackerCheck(info);
                    case HSM_HANDLE_INET_CONDITION_CHANGE:
                    {
                        int netType = msg.arg1;
                        int condition = msg.arg2;
                        handleInetConditionChange(netType, condition);
                        break;
                    }
                    case HSM_HANDLE_INET_CONDITION_HOLD_END:
                    {
                        int netType = msg.arg1;
                        int sequence = msg.arg2;
                        handleInetConditionHoldEnd(netType, sequence);
                        break;
                    }
                    case HSM_HANDLE_REQUEST_NET_TRANSITION_WAKELOCK:
                    {
                        String forWhom = (String) msg.obj;
                        // This will automatically be cleared after 60 seconds or
                        // a network becomes CONNECTED, whichever happens first.
                        // The timer is started by the first caller and not
                        // restarted by subsequent callers.

                        if (mNetTransitionWakeLock.isHeld()) break;

                        mNetTransitionWakeLockSerialNumber++;
                        mNetTransitionWakeLock.acquire();
                        mNetTransitionWakeLockCausedBy = forWhom;

                        sendMessageDelayed(obtainMessage(
                                    EVENT_CLEAR_NET_TRANSITION_WAKELOCK,
                                    mNetTransitionWakeLockSerialNumber,
                                    INVALID_MSG_ARG), mNetTransitionWakeLockTimeout);
                        break;
                    }
                    case HSM_EVENT_ENFORCE_PREFERENCE:
                        enforcePreference();
                        break;
                    case HSM_EVENT_RESTORE_DNS:
                    {
                        if (mActiveDefaultNetwork != -1) {
                            handleDnsConfigurationChange(mActiveDefaultNetwork);
                        }
                        break;
                    }
                    default:
                        loge(getCurrentState().getName() + " ignoring unhandled message");
                }
                // runs in system-server and is the root parent state for all
                // never return not_handled
                return true;
            }
        }

        /**
         * Smart Connectivity State.
         * CS will be in this state when CNE smart connectivity is enabled and FMC is disabled.
         * Supports simultaneous wif + mobile connections.
         * Is a parent state for other smart connectivity states.
         */
        private final class SmartConnectivityState extends State {

            private boolean avoidUnsuitableWifi;

            public SmartConnectivityState () {
                avoidUnsuitableWifi = true;
            }

            @Override
            public void enter() {
                if (DBG) log( "ConnectivityServiceHSM entering " + getCurrentState().getName());
                //reset metric of default routes for wwan & wifi
                mRouteAttributes[TYPE_WIFI].setMetric(0);
                mRouteAttributes[TYPE_MOBILE].setMetric(0);
                //make wifi higher priority dns than 3g by default.
                myDefaultDnsNet = TYPE_WIFI;
            }

            @Override
            public void exit() {
                if (DBG) log( "ConnectivityServiceHSM leaving " + getCurrentState().getName());
            }

            /**
             * connected.
             */
            private boolean isNetworkSimultaneitySupported(NetworkInfo info) {
                final int type = info.getType();
                boolean ret = false;
                if (mNetConfigs[type].isDefault()) {
                    mConnectedDefaultNetworks.add(type);
                    if (avoidUnsuitableWifi && (mConnectedDefaultNetworks.size() > 1)) {
                        ret = true;
                    }
                }
                return ret;
            }

            private void handleCaptivePortalTrackerCheck(NetworkInfo info) {
                if (DBG) log("Captive portal check " + info);
                int type = info.getType();
                final NetworkStateTracker thisNet = mNetTrackers[type];
                if (mNetConfigs[type].isDefault()) {
                    if (mActiveDefaultNetwork != -1 && mActiveDefaultNetwork != type) {
                        if (isNewNetTypePreferredOverCurrentNetType(type)) {
                            if (DBG) log("Captive check on " + info.getTypeName());
                            mCaptivePortalTracker.detectCaptivePortal(new NetworkInfo(info));
                        } else {
                            if (DBG) log("Captive portal check skipped on low priority net "
                                    + info.getTypeName());
                        }
                    }
                }

                thisNet.captivePortalCheckComplete();
            }

            @Override
            public boolean processMessage(Message msg) {
                if (DBG) log(getCurrentState().getName() + ".processMessage what=" + msg.what);
                NetworkInfo info = null;
                boolean ret = NOT_HANDLED; // by default leave all events as not_handled
                switch (msg.what) {
                    case HSM_HANDLE_CONNECT :
                    {
                        info = (NetworkInfo) msg.obj;
                        mConnectedDefaultNetworks.add(info.getType());
                        if (isNetworkSimultaneitySupported(info)) {
                            log("Dual Connectivity Mode detected");
                            deferMessage(msg);
                            if (mActiveDefaultNetwork == TYPE_WIFI) {
                                transitionTo(mWifiDefaultState);
                            } else {
                                transitionTo(mMobileDefaultState);
                            }
                            ret = HANDLED;
                        }
                        break;
                    }
                    case HSM_HANDLE_DISCONNECT:
                    {
                        info = (NetworkInfo) msg.obj;
                        mConnectedDefaultNetworks.remove(info.getType());
                        break;
                    }
                    case HSM_HANDLE_CAPTIVE_PORTAL_CHECK:
                    {
                        info = (NetworkInfo) msg.obj;
                        handleCaptivePortalTrackerCheck(info);
                        ret = HANDLED;
                        break;
                    }
                    case EVENT_AVOID_UNSUITABLE_WIFI:
                    {
                        avoidUnsuitableWifi = (msg.arg1 == 1);
                        log("dual network support " + (avoidUnsuitableWifi ? "enabled":"disabled"));
                        if (avoidUnsuitableWifi && (mActiveDefaultNetwork == TYPE_WIFI)) {
                            mNetTrackers[TYPE_MOBILE].reconnect();
                        }
                        ret = HANDLED;
                        break;
                    }
                    case HSM_HANDLE_REQUEST_NET_TRANSITION_WAKELOCK:
                    {
                        if (mActiveDefaultNetwork == TYPE_MOBILE) {
                            if (VDBG) log("NetTransition wakelock is not needed");
                            ret = HANDLED;
                        }
                        break;
                    }
                    default: ret = NOT_HANDLED;
                }
                return ret;
            }
        }

        /**
         * Dual connectivity Mobile default state.
         * Mobile is treated as the mActiveDefaultNetwork in this state
         */
        private final class MobileDefaultState extends DualConnectivityState {
            public MobileDefaultState () {
                myDefaultNet = TYPE_MOBILE;
                otherDefaultNet = TYPE_WIFI;
            }

            @Override
            public void enter() {
                if (DBG) log( "ConnectivityServiceHSM entering " + getCurrentState().getName());
                runOnEnter();
            }

            @Override
            public void exit() {
                if (DBG) log( "ConnectivityServiceHSM leaving " + getCurrentState().getName());
            }

            @Override
            protected void transitionToOther() {
                if (DBG) log(getCurrentState().getName() + " transitionToOther");
                transitionTo(mWifiDefaultState); // transition to mWifiDefaultState
            }

        }

        /**
         * Dual connectivity Wifi default state.
         * Wifi is treated as the mActiveDefaultNetwork in this state
         */
        private final class WifiDefaultState extends DualConnectivityState {
            public WifiDefaultState () {
                myDefaultNet = TYPE_WIFI;
                otherDefaultNet = TYPE_MOBILE;
            }

            @Override
            public void enter() {
                if (DBG) log( "ConnectivityServiceHSM entering " + getCurrentState().getName());
                runOnEnter();
            }

            @Override
            public void exit() {
                if (DBG) log( "ConnectivityServiceHSM leaving " + getCurrentState().getName());
            }

            @Override
            protected void transitionToOther() {
                if (DBG) log(getCurrentState().getName() + " transitionToOther");
                transitionTo(mMobileDefaultState); // transition to mMobileDefaultState
            }

        }

        /**
         * Abstract class that provides framework to support 3G and Wifi
         * simultaneously.
         * All dual connectivity states except FMC must extend from this class
         */
        private abstract class DualConnectivityState extends State {

            protected int myDefaultNet;
            protected int otherDefaultNet,
                          mOtherDefaultInetCondition = 0,
                          mOtherDefaultInetConditionPublished = 0,
                          mOtherDefaultConnectionSequence = 0;
            protected boolean mOtherInetConditionChangeInFlight = false;

            @Override
            public boolean processMessage(Message msg) {
                if (DBG) log(getCurrentState().getName() + ".processMessage what=" + msg.what);
                NetworkInfo info;
                boolean ret = NOT_HANDLED; // by default leave all messages as unhandled
                switch (msg.what) {
                    case HSM_HANDLE_CONNECT:
                    {
                        info = (NetworkInfo) msg.obj;
                        int r = handleConnect(info);
                        if (r == 0) {
                            ret = HANDLED; // handled connect in this state
                        } else if (r == -1) {
                            ret = NOT_HANDLED; // let parent process this event
                        } else {
                            deferMessage(msg);
                            transitionTo(mSmartConnectivityState);
                            ret = HANDLED; // state change, transition to parent and then process
                        }
                        break;
                    }
                    case HSM_HANDLE_DISCONNECT:
                    {
                        info = (NetworkInfo) msg.obj;
                        int r = handleDisconnect(info); //private handler
                        if (r == 0) {
                            ret = NOT_HANDLED;
                        } else if (r == -1) {
                            deferMessage(msg);
                            transitionTo(mSmartConnectivityState);
                            ret = HANDLED;
                        } else {
                            transitionTo(mSmartConnectivityState);
                            ret = HANDLED;
                        }
                        break;
                    }
                    case HSM_HANDLE_CONNECTIVITY_CHANGE:
                    {
                        int type = msg.arg1;
                        boolean doReset = (msg.arg2 == 1);
                        handleConnectivityChange(type, doReset); //private handler
                        ret = HANDLED;
                        break;
                    }
                    case HSM_HANDLE_DNS_CONFIGURATION_CHANGE:
                    {
                        int type = msg.arg1;
                        handleDnsConfigurationChange(type); //private handler
                        ret = HANDLED;
                        break;
                    }
                    case HSM_HANDLE_CONNECTION_FAILURE:
                        break;
                    case HSM_HANDLE_INET_CONDITION_CHANGE:
                    {
                        int netType = msg.arg1;
                        int condition = msg.arg2;
                        if (handleInetConditionChange(netType, condition)) {
                            ret = HANDLED;
                        }
                        break;
                    }
                    case HSM_HANDLE_INET_CONDITION_HOLD_END:
                    {
                        int netType = msg.arg1;
                        int sequence = msg.arg2;
                        if (handleInetConditionHoldEnd(netType, sequence)) {
                            ret = HANDLED;
                        }
                        break;
                    }
                    case HSM_HANDLE_REQUEST_NET_TRANSITION_WAKELOCK:
                    {
                        if (VDBG) log("NetTransition wakelock is not needed");
                        ret = HANDLED;
                        break;
                    }
                    case HSM_EVENT_ENFORCE_PREFERENCE:
                    {
                        loge("enforcing network preference not allowed in dual connectivity state");
                        ret = HANDLED;
                        break;
                    }
                    case HSM_EVENT_RESTORE_DNS:
                    {
                        handleDnsConfigurationChange(myDefaultDnsNet);
                        ret = HANDLED;
                        break;
                    }
                    case EVENT_CONNECTIVITY_SWITCH:
                    {
                        String reason = (String) msg.obj;
                        int type = msg.arg1;
                        if ( ! handleConnectivitySwitch(type, reason)) {
                            deferMessage(msg);
                            transitionToOther();
                        }
                        ret = HANDLED;
                        break;
                    }
                    case EVENT_REPRIORITIZE_DNS:
                    {
                        int type = msg.arg1;
                        if (type != myDefaultDnsNet) {
                            handleDnsReprioritization(type);
                        } else {
                            logw("Dns is already prioritized for network " + type);
                        }
                        ret = HANDLED;
                        break;
                    }
                    case EVENT_AVOID_UNSUITABLE_WIFI:
                    {
                        deferMessage(msg);
                        ret = HANDLED;
                        break;
                    }
                    default:
                        ret = NOT_HANDLED;
                        if (DBG) {
                            log(getCurrentState().getName() +
                                     ": no handler for message="+ msg.what);
                        }
                }
                return ret;
            }

            /**
             * state specific route updates to be run as the first thing on
             * entering this state.
             */
            protected void runOnEnter() {
                if (DBG) log(getCurrentState().getName() + " runOnEnter");
                // reset to implementation state specific route metric and update default route
                mRouteAttributes[myDefaultNet].setMetric(0);
                mRouteAttributes[otherDefaultNet].setMetric(20);
                updateDefaultRouteMetric(myDefaultNet);
            }

            /**
             * To be implemented by implementing class of dualconnectivitystate
             * to transition from my to other default net state.
             */
            protected abstract void transitionToOther();


            /**
             * Broadcasts an intent indicating switch in default connectivity
             * from one network to another.
             * Modelled on the intent sent out in handleConnectionFailure
             * Is valid only in dual network states
             * Reason is provided by the implementing class
             */
            protected void sendConnectivitySwitchBroadcast(String reason) {

                if (DBG) log(getCurrentState().getName() + " sendConnectivitySwitchBroadcast");

                NetworkInfo newNetInfo = mNetTrackers[myDefaultNet].getNetworkInfo();

                Intent intent = new Intent(ConnectivityManager.CONNECTIVITY_ACTION);
                intent.putExtra(ConnectivityManager.EXTRA_NETWORK_INFO, new NetworkInfo(newNetInfo));
                intent.putExtra(ConnectivityManager.EXTRA_NETWORK_TYPE, newNetInfo.getType());
                if (reason != null && reason.length() > 0) {
                    intent.putExtra(ConnectivityManager.EXTRA_REASON, reason);
                }
                intent.putExtra(ConnectivityManager.EXTRA_IS_FAILOVER, true);
                intent.putExtra(ConnectivityManager.EXTRA_INET_CONDITION,
                                mDefaultInetConditionPublished);

                final Intent immediateIntent = new Intent(intent);
                immediateIntent.setAction(CONNECTIVITY_ACTION_IMMEDIATE);
                sendStickyBroadcast(immediateIntent);
                sendStickyBroadcast(intent);
            }

            /**
             * Switches default connectivity from one active default to another.
             * Same method is used by both pre-transition state and the post
             * transition state.
             * handles routes and intents in post transition
             * returns:
             *      true  - event handled completely, don't transition.
             *      false - event handled in old state, defermsg and transition to new state
             *              and handle the rest of it
             */
            protected boolean handleConnectivitySwitch (int netType, String reason) {

                if (DBG) log(getCurrentState().getName() + " handleConnectivitySwitch");
                boolean ret = true; // true - dont transition, false - transition

                if ( ! mConnectedDefaultNetworks.contains(netType) ) {
                    logw(" Network " + netType + " not supported for default connectivity");
                    return ret;
                }
                // mactive isn't updated yet, is updated post switch.
                if (mActiveDefaultNetwork == netType) {
                    logw(" Network" + netType + " is already default");
                    return ret;
                }
                if (myDefaultNet == netType) {
                    //post switch handling in new state
                    mActiveDefaultNetwork = myDefaultNet;
                    updateDefaultRouteMetric(otherDefaultNet);
                    // Tell VPN of default net switch
                    mVpn.interfaceStatusChanged(
                            mNetTrackers[otherDefaultNet].getLinkProperties().getInterfaceName(),
                            false);
                    /**
                    * moved this logic from orig handleConnectivityChange to here, to allow only one
                    * default http proxy at any given time.
                    */
                    QcConnectivityService.this.handleApplyDefaultProxy(
                        mNetTrackers[myDefaultNet].getLinkProperties().getHttpProxy());
                    sendConnectivitySwitchBroadcast(reason);
                } else {
                    //pre switch handling in old state
                    removeDefaultRoutes(myDefaultNet);
                    ret = false;  // transition to otherstate
                }
                return ret;
            }

            /**
             * Updates name server priority and sets up the dns cache.
             * can be double null in which case it will only update name server
             * priority.
             * Caller must grab mDnsLock
             */
            private void updateDnsLocked(String iface,
                                         Collection<InetAddress> netDnses, String domains) {

                if (DBG) log(getCurrentState().getName() + " updateDns");
                boolean changed = false;
                int last = 0;
                List<InetAddress> dnses = new ArrayList<InetAddress>();

                LinkProperties mlp = mNetTrackers[myDefaultNet].getLinkProperties();
                LinkProperties olp = mNetTrackers[otherDefaultNet].getLinkProperties();

                if (mlp != null) dnses.addAll(mlp.getDnses());
                if (olp != null) {
                    if (otherDefaultNet == myDefaultDnsNet) {
                        dnses.addAll(0, olp.getDnses());
                    } else {
                        dnses.addAll(olp.getDnses());
                    }
                }

                if (dnses.size() == 0 && mDefaultDns != null) {
                    dnses.add(mDefaultDns);
                    if (DBG) {
                        loge("no dns provided - using " + mDefaultDns.getHostAddress());
                    }
                    changed = true;
                }
                for (InetAddress dns : dnses) {
                    ++last;
                    String key = "net.dns" + last;
                    String value = dns.getHostAddress();
                    if (!changed && value.equals(SystemProperties.get(key))) {
                        continue;
                    }
                    if (VDBG) log("adding dns " + value );
                    changed = true;
                    SystemProperties.set(key, value);
                }
                for (int i = last + 1; i <= mNumDnsEntries; ++i) {
                    String key = "net.dns" + i;
                    if (VDBG) log("erasing " + key);
                    changed = true;
                    SystemProperties.set(key, "");
                }
                mNumDnsEntries = last;

                if (changed) {
                    try {
                        if (iface != null && netDnses != null) {
                            // only update interface dns cache for the changed iface.
                            mNetd.setDnsServersForInterface( iface,
                                    NetworkUtils.makeStrings(netDnses), domains );
                        }
                        // set appropriate default iface for dns cache
                        String defDnsIface = null;
                        if (myDefaultDnsNet == myDefaultNet && mlp != null) {
                            defDnsIface = mlp.getInterfaceName();
                        } else if (olp != null) {
                            defDnsIface = olp.getInterfaceName();
                        }
                        if (!TextUtils.isEmpty(defDnsIface)) {
                            mNetd.setDefaultInterfaceForDns(defDnsIface);
                        }
                    } catch (Exception e) {
                        if (VDBG) loge("exception setting default dns interface: " + e);
                    }
                }
            }

            /**
             * Reprioritizes the specified networks name servers.
             */
            protected void handleDnsReprioritization (int netType) {

                if (DBG) log(getCurrentState().getName() + " handleDnsReprioritization");
                // only change dns priority for networks we can handle in this state.
                if (!mConnectedDefaultNetworks.contains(netType)) {
                    logw("Cannot prioritize dns for unsupported type" + netType);
                    return;
                }

                log("Prioritizing Dns for network " + netType);

                synchronized (mDnsLock) {
                    myDefaultDnsNet = netType;
                    if (!mDnsOverridden) {
                        updateDnsLocked(null, null, null);
                    }
                }
                flushVmDnsCache();
            }

            /**
             * Same as default state's implementation, with exception of calling
             * custom updateDnsLocked() method.
             */
            protected void handleDnsConfigurationChange(int netType) {
                // add default net's dns entries
                NetworkStateTracker nt = mNetTrackers[netType];
                if (nt != null && nt.getNetworkInfo().isConnected() && !nt.isTeardownRequested()) {
                    LinkProperties p = nt.getLinkProperties();
                    if (p == null) return;
                    Collection<InetAddress> dnses = p.getDnses();
                    if (mNetConfigs[netType].isDefault()) {
                        String network = nt.getNetworkInfo().getTypeName();
                        synchronized (mDnsLock) {
                            if (!mDnsOverridden) {
                                updateDnsLocked(p.getInterfaceName(), dnses, p.getDomains());
                            }
                        }
                    } else {
                        try {
                            mNetd.setDnsServersForInterface(p.getInterfaceName(),
                                    NetworkUtils.makeStrings(dnses), p.getDomains());
                        } catch (Exception e) {
                            if (DBG) loge("exception setting dns servers: " + e);
                        }
                        // set per-pid dns for attached secondary nets
                        List<Integer> pids = mNetRequestersPids[netType];
                        for (Integer pid : pids) {
                            try {
                                mNetd.setDnsInterfaceForPid(p.getInterfaceName(), pid);
                            } catch (Exception e) {
                                Slog.e(TAG, "exception setting interface for pid: " + e);
                            }
                        }
                    }
                    flushVmDnsCache();
                }
            }

            /**
             * Handle a {@code DISCONNECTED} event of other Default network when
             * my default network is still connected.
             * Defer message to parent state for processing of my default net type.
             * handle non-defaults in parent
             * handle and transition to parent when other disconnects.
             * @param info the {@code NetworkInfo} for the network
             * returns:
             *      0 - NOT_HANDLED
             *     -1 - deferMsg and tansition to parent
             *     -2 - transition to parent
             */
            protected int handleDisconnect(NetworkInfo info) {

                if (DBG) log(getCurrentState().getName() + " handleDisconnect");
                int type = info.getType();

                // dont handle network types other than my and other DefaultNet
                if ( !mNetConfigs[type].isDefault() || !mConnectedDefaultNetworks.contains(type)) {
                    return 0;
                }

                if (type == myDefaultNet) {
                    if (myDefaultDnsNet == type) { // reprioritize dns to other
                        handleDnsReprioritization(otherDefaultNet);
                    }
                    mConnectedDefaultNetworks.remove(type);
                    // reset ActiveDefault to other and send broadcast
                    mActiveDefaultNetwork = otherDefaultNet;
                    NetworkInfo otherInfo = mNetTrackers[mActiveDefaultNetwork].getNetworkInfo();
                    otherInfo.setFailover(true);
                    sendConnectedBroadcast(otherInfo);
                    return -1; // defer and transition to parent
                }

                //release the transitionWakeLock as some NetTrackers hold it after they disconnect.
                // We already have default network, release the transition wakelock immediately
                String causedBy = null;
                synchronized (QcConnectivityService.this) {
                    if (mNetTransitionWakeLock.isHeld()) {
                        mNetTransitionWakeLock.release();
                        causedBy = mNetTransitionWakeLockCausedBy;
                    }
                }
                if (causedBy != null) {
                    log("NetTransition Wakelock for " +causedBy+ " released because of disconnect");
                }

                //handle other def net disconnect
                mNetTrackers[type].setTeardownRequested(false);

                Intent intent = new Intent(ConnectivityManager.CONNECTIVITY_ACTION);
                intent.putExtra(ConnectivityManager.EXTRA_NETWORK_INFO, info);
                if (info.isFailover()) {
                    intent.putExtra(ConnectivityManager.EXTRA_IS_FAILOVER, true);
                    info.setFailover(false);
                }
                if (info.getReason() != null) {
                    intent.putExtra(ConnectivityManager.EXTRA_REASON, info.getReason());
                }
                if (info.getExtraInfo() != null) {
                    intent.putExtra(ConnectivityManager.EXTRA_EXTRA_INFO,
                            info.getExtraInfo());
                }
                intent.putExtra(ConnectivityManager.EXTRA_OTHER_NETWORK_INFO,
                        mNetTrackers[myDefaultNet].getNetworkInfo());
                intent.putExtra(ConnectivityManager.EXTRA_INET_CONDITION,
                                mDefaultInetConditionPublished);

                // Reset interface if no other connections are using the same interface
                boolean doReset = true;
                LinkProperties linkProperties = mNetTrackers[type].getLinkProperties();
                if (linkProperties != null) {
                    String oldIface = linkProperties.getInterfaceName();
                    if (TextUtils.isEmpty(oldIface) == false) {
                        for (NetworkStateTracker networkStateTracker : mNetTrackers) {
                            if (networkStateTracker == null) continue;
                            NetworkInfo networkInfo = networkStateTracker.getNetworkInfo();
                            if (networkInfo.isConnected() && networkInfo.getType() != type) {
                                LinkProperties l = networkStateTracker.getLinkProperties();
                                if (l == null) continue;
                                if (oldIface.equals(l.getInterfaceName())) {
                                    doReset = false;
                                    break;
                                }
                            }
                        }
                    }
                }
                // do this before we broadcast the change - use custom handler
                handleConnectivityChange(type, doReset);

                final Intent immediateIntent = new Intent(intent);
                immediateIntent.setAction(CONNECTIVITY_ACTION_IMMEDIATE);
                sendStickyBroadcast(immediateIntent);
                sendStickyBroadcastDelayed(intent, getConnectivityChangeDelay());

                // reprioritize dns to remove other net's dnses
                if (myDefaultDnsNet == type) {
                    handleDnsReprioritization(myDefaultNet);
                }
                //Stop tracking other default network
                mConnectedDefaultNetworks.remove(type);
                return -2; // true - transition to parent state.
            }

            /**
             * Handle a {@code CONNECTED} event of other default network.
             * If a higher priority network comes up, disconnect connected defaults and
             * transition to parent state for processing this event.
             * If a lower priority or non-default network comes up, process event in parent state.
             */
            protected boolean isHigherPriorityNet(int type) {

                if (DBG) log(getCurrentState().getName() + " isHigherPriorityNet");
                boolean ret = false;
                if (mConnectedDefaultNetworks.isHigherPriorityNetwork(type)) {
                    // a higher priority network is connected disconnect our active default
                    // networks, defer msg and transition to parent state
                    teardown(mNetTrackers[otherDefaultNet]);
                    mConnectedDefaultNetworks.remove(otherDefaultNet);
                    teardown(mNetTrackers[myDefaultNet]);
                    mConnectedDefaultNetworks.remove(myDefaultNet);
                    ret = true;
                } else {
                    teardown(mNetTrackers[type]);
                }
                return ret;
            }

            /**
             * Handle a {@code CONNECTED} event of the my and other
             * default network types.
             * returns:
             *      0 - handled connect for my and other and lower prio defaults
             *     -1 - NOT_HANDLED for non-default types
             *     -2 - Higher pri net connected, deferMsg and transition
             */
            protected int handleConnect(NetworkInfo info) {

                if (DBG) log(getCurrentState().getName() + " handleConnect");
                final int type = info.getType();
                final NetworkStateTracker thisNet = mNetTrackers[type];

                // handle non default networks in parent state.
                if ( ! mNetConfigs[type].isDefault() ) {
                   return -1;
                }

                // handle lower prio default in this state, higher prio defaults
                // in parent state by transitioning to it.
                if (! mConnectedDefaultNetworks.contains(type)) {
                    return (isHigherPriorityNet(type) ? -2 : 0);
                }

                // handle connect event for active defaults
                // release the transitionWakeLock as some NetTrackers hold it after they disconnect.
                // We already have default network, release the transition wakelock immediately
                String causedBy = null;
                synchronized (QcConnectivityService.this) {
                    if (mNetTransitionWakeLock.isHeld()) {
                        mNetTransitionWakeLock.release();
                        causedBy = mNetTransitionWakeLockCausedBy;
                    }
                }
                if (causedBy != null) {
                    log("NetTransition Wakelock for " + causedBy + " released because of connect");
                }

                if (type == myDefaultNet) {
                    mDefaultInetConditionPublished = 0;
                    mDefaultConnectionSequence++;
                    mInetConditionChangeInFlight = false;
                } else {
                    mOtherDefaultInetConditionPublished = 0;
                    mOtherDefaultConnectionSequence++;
                    mOtherInetConditionChangeInFlight = false;
                }
                thisNet.setTeardownRequested(false);
                updateNetworkSettings(thisNet);
                handleConnectivityChange(type, false); // private handler
                if (type != otherDefaultNet) { // squelch broadcast for other default net
                    sendConnectedBroadcastDelayed(info, getConnectivityChangeDelay());
                } else if (type == TYPE_WIFI) {
                    sendConnectivityUpBroadcast(type);
                }

                // notify battery stats service about this network
                final String iface = thisNet.getLinkProperties().getInterfaceName();
                if (iface != null) {
                    try {
                        BatteryStatsService.getService().noteNetworkInterfaceType(iface, type);
                    } catch (RemoteException e) {
                        // ignored; service lives in system_server
                    }
                }
                return 0;
            }

            /**
             * handles inet condition change for otherDefaultNet.
             * Defers processing of other net types to parent state
             */
            protected boolean handleInetConditionChange(int netType, int condition) {

                if (DBG) log(getCurrentState().getName() + " handleInetConditionChange");
                if (netType != otherDefaultNet) {
                    return false;
                }

                if (VDBG) {
                    log("handleInetConditionChange: net=" +
                            netType + ", condition=" + condition +
                            ", for other active default Network=" + netType);
                }

                mOtherDefaultInetCondition = condition;
                int delay;
                if (mOtherInetConditionChangeInFlight == false) {
                    if (VDBG) log("handleInetConditionChange: starting a change hold");
                    // setup a new hold to debounce this
                    if (mOtherDefaultInetCondition > 50) {
                        delay = Settings.Global.getInt(mContext.getContentResolver(),
                                Settings.Global.INET_CONDITION_DEBOUNCE_UP_DELAY, 500);
                    } else {
                        delay = Settings.Global.getInt(mContext.getContentResolver(),
                        Settings.Global.INET_CONDITION_DEBOUNCE_DOWN_DELAY, 3000);
                    }
                    mOtherInetConditionChangeInFlight = true;
                    sendMessageDelayed(obtainMessage(EVENT_INET_CONDITION_HOLD_END,
                                otherDefaultNet, mOtherDefaultConnectionSequence), delay);
                } else {
                    // we've set the new condition, when this hold ends that will get picked up
                    if (VDBG) {
                        log("handleInetConditionChange:" +
                            " currently in hold - not setting new end evt");
                    }
                }
                return true;
            }

            /**
             * handles inet condition hold end for otherDefaultNet.
             * Defers processing of other net types to parent
             */
            protected boolean handleInetConditionHoldEnd(int netType, int sequence) {

                if (DBG) log(getCurrentState().getName() + " handleInetConditionHoldEnd");
                if (netType != otherDefaultNet) {
                    return false;
                }

                if (DBG) {
                    log("handleInetConditionHoldEnd: net=" + netType +
                            ", condition=" + mOtherDefaultInetCondition +
                            ", published condition=" + mOtherDefaultInetConditionPublished);
                }
                mOtherInetConditionChangeInFlight = false;

                if (mOtherDefaultConnectionSequence != sequence) {
                    if (DBG) {
                        log("handleInetConditionHoldEnd: " +
                            "event hold for obsolete network - ignoring");
                    }
                    return true;
                }

                NetworkInfo networkInfo = mNetTrackers[otherDefaultNet].getNetworkInfo();
                if (networkInfo.isConnected() == false) {
                    if (DBG) log("handleInetConditionHoldEnd: default network " +
                            netType + " not connected - ignoring");
                    return true;
                }
                mOtherDefaultInetConditionPublished = mOtherDefaultInetCondition;
                sendInetConditionBroadcast(networkInfo);
                return true;
            }
            /**
             * Smart Connectivity networks handleConnectivityChange method.
             * Pretty much the same as default state's method barring exception
             * that it calls a private update route method and does not set http
             * proxy.
             * -------------------------------
             * After a change in the connectivity state of a network. We're mainly
             * concerned with making sure that the list of DNS servers is set up
             * according to which networks are connected, and ensuring that the
             * right routing table entries exist.
             */
            private void handleConnectivityChange(int netType, boolean doReset) {
                int resetMask = doReset ? NetworkUtils.RESET_ALL_ADDRESSES : 0;

                /*
                 * If a non-default network is enabled, add the host routes that
                 * will allow it's DNS servers to be accessed.
                 */
                handleDnsConfigurationChange(netType); //use custom handler

                LinkProperties curLp = mCurrentLinkProperties[netType];
                LinkProperties newLp = null;

                if (mNetTrackers[netType].getNetworkInfo().isConnected()) {
                    newLp = mNetTrackers[netType].getLinkProperties();
                    if (VDBG) {
                        log("handleConnectivityChange: changed linkProperty[" + netType + "]:" +
                                " doReset=" + doReset + " resetMask=" + resetMask +
                                "\n   curLp=" + curLp +
                                "\n   newLp=" + newLp);
                    }

                    if (curLp != null) {
                        if (curLp.isIdenticalInterfaceName(newLp)) {
                            CompareResult<LinkAddress> car = curLp.compareAddresses(newLp);
                            if ((car.removed.size() != 0) || (car.added.size() != 0)) {
                                for (LinkAddress linkAddr : car.removed) {
                                    if (linkAddr.getAddress() instanceof Inet4Address) {
                                        resetMask |= NetworkUtils.RESET_IPV4_ADDRESSES;
                                    }
                                    if (linkAddr.getAddress() instanceof Inet6Address) {
                                        resetMask |= NetworkUtils.RESET_IPV6_ADDRESSES;
                                    }
                                }
                                if (DBG) {
                                    log("handleConnectivityChange: addresses changed" +
                                            " linkProperty[" + netType + "]:" + " resetMask=" + resetMask +
                                            "\n   car=" + car);
                                }
                            } else {
                                if (DBG) {
                                    log("handleConnectivityChange: address are the same reset per doReset" +
                                           " linkProperty[" + netType + "]:" +
                                           " resetMask=" + resetMask);
                                }
                            }
                        } else {
                            resetMask = NetworkUtils.RESET_ALL_ADDRESSES;
                            if (DBG) {
                                log("handleConnectivityChange: interface not not equivalent reset both" +
                                        " linkProperty[" + netType + "]:" +
                                        " resetMask=" + resetMask);
                            }
                        }
                    }
                } else {
                    if (VDBG) {
                        log("handleConnectivityChange: changed linkProperty[" + netType + "]:" +
                                " doReset=" + doReset + " resetMask=" + resetMask +
                                "\n  curLp=" + curLp +
                                "\n  newLp= null");
                    }
                }
                mCurrentLinkProperties[netType] = newLp;
                    boolean resetDns = updateRoutes( newLp,
                                                     curLp,
                                                     mNetConfigs[netType].isDefault(),
                                                     mRouteAttributes[netType] );

                if (resetMask != 0 || resetDns) {
                    if (curLp != null) {
                        for (String iface : curLp.getAllInterfaceNames()) {
                            if (TextUtils.isEmpty(iface) == false) {
                                if (resetMask != 0) {
                                    if (DBG) log("resetConnections(" + iface + ", " + resetMask + ")");
                                    NetworkUtils.resetConnections(iface, resetMask);

                                    // Tell VPN the interface is down. It is a temporary
                                    // but effective fix to make VPN aware of the change.
                                    if ((resetMask & NetworkUtils.RESET_IPV4_ADDRESSES) != 0) {
                                        mVpn.interfaceStatusChanged(iface, false);
                                    }
                                }
                                if (resetDns) {
                                    flushVmDnsCache();
                                    if (VDBG) log("resetting DNS cache for " + iface);
                                    try {
                                        mNetd.flushInterfaceDnsCache(iface);
                                    } catch (Exception e) {
                                        // never crash - catch them all
                                        if (DBG) loge("Exception resetting dns cache: " + e);
                                    }
                                }
                            } else {
                                loge("Can't reset connection for type "+netType);
                            }
                        }
                    }
                }

                // Update 464xlat state.
                NetworkStateTracker tracker = mNetTrackers[netType];
                if (mClat.requiresClat(netType, tracker)) {
                    // If the connection was previously using clat, but is not using it now, stop the clat
                    // daemon. Normally, this happens automatically when the connection disconnects, but if
                    // the disconnect is not reported, or if the connection's LinkProperties changed for
                    // some other reason (e.g., handoff changes the IP addresses on the link), it would
                    // still be running. If it's not running, then stopping it is a no-op.
                    if (Nat464Xlat.isRunningClat(curLp) && !Nat464Xlat.isRunningClat(newLp)) {
                        mClat.stopClat();
                    }
                    // If the link requires clat to be running, then start the daemon now.
                    if (mNetTrackers[netType].getNetworkInfo().isConnected()) {
                        mClat.startClat(tracker);
                    } else {
                        mClat.stopClat();
                    }
                }

                // TODO: Temporary notifying upstread change to Tethering.
                //       @see bug/4455071
                /** Notify TetheringService if interface name has been changed. */
                if (TextUtils.equals(mNetTrackers[netType].getNetworkInfo().getReason(),
                                     PhoneConstants.REASON_LINK_PROPERTIES_CHANGED)) {
                    if (isTetheringSupported()) {
                        mTethering.handleTetherIfaceChange();
                    }
                }
            }

            /**
             * Updates route metric of mActiveDefaultNetwork when transitioning
             * from another network.
             */
            protected void updateDefaultRouteMetric(int type) {

                if (DBG) log(getCurrentState().getName() + " updateDefaultRouteMetric");
                LinkProperties lp = mCurrentLinkProperties[type];
                if (lp == null) return;

                for (RouteInfo r : lp.getRoutes()) {
                    if (r == null || r.isHostRoute()) continue;
                    addRoute(lp, r, 0, mRouteAttributes[type].getMetric());
                }
            }

            /**
             * Removes default routes for default networks if type is all (-1).
             * Else removes default route for specified type
             */
            protected void removeDefaultRoutes (int netType) {

                if (DBG) log(getCurrentState().getName() + " removeDefaultRoutes");
                if (netType == -1) {
                    if (DBG) log("removing default routes for all networks");
                    for (Integer type : mConnectedDefaultNetworks.getActiveDefaults()){
                        LinkProperties p = mCurrentLinkProperties[type.intValue()];
                        if (p == null ) continue;
                        for (RouteInfo r : p.getRoutes()) {
                            if (r != null && r.isDefaultRoute()) {
                                QcConnectivityService.this.removeRoute(p, r, TO_DEFAULT_TABLE);
                            }
                        }
                    }
                } else if (mConnectedDefaultNetworks.contains(netType)) {
                    if (DBG) log("removing default routes for " + netType);
                    LinkProperties p = mCurrentLinkProperties[netType];
                    if (p == null) return;
                    for (RouteInfo r : p.getRoutes()) {
                        if (r != null && r.isDefaultRoute()) {
                            QcConnectivityService.this.removeRoute(p, r, TO_DEFAULT_TABLE);
                        }
                    }
                }
            }

            /**
             * Custom addRoute implementation for smart connectivity states.
             * Supports metric routes for default networks.
             * Manages mAddedRoutes appropriately for dual default Network State.
             * Does not support adding route to secondary table.
             */
            protected boolean addRoute(LinkProperties lp, RouteInfo r,
                    int cycleCount, int defaultRouteMetric) {

                if (DBG) log(getCurrentState().getName() + " addRoute");
                if (lp == null || r == null) {
                    if (DBG) log("addRoute got unexpected null: " + lp + ", "+ r);
                }

                if (cycleCount > MAX_HOSTROUTE_CYCLE_COUNT) {
                    loge("Error adding route - too much recursion");
                    return false;
                }

                int metric = 0;
                String ifaceName = r.getInterface();
                if (ifaceName == null) {
                    loge("Error adding route - no interface name");
                    return false;
                }

                // use state specific metric for default routes
                if ( ! r.isHostRoute() )metric = defaultRouteMetric;
                if (r.hasGateway()) {
                    RouteInfo bestRoute =
                        RouteInfo.selectBestRoute(lp.getAllRoutes(), r.getGateway());
                    if (bestRoute != null) {
                        if (bestRoute.getGateway().equals(r.getGateway())) {
                            //if there is no better route, add the implied hostroute for our gateway
                            bestRoute = RouteInfo.makeHostRoute(r.getGateway(), ifaceName);
                        } else {
                            // if we will connect to our gateway through another route, add a direct
                            // route to it's gateway
                            bestRoute =
                                RouteInfo.makeHostRoute(r.getGateway(),
                                                        bestRoute.getGateway(),
                                                        ifaceName);
                        }
                        addRoute(lp, bestRoute, cycleCount+1, metric);
                    }
                }

                if (VDBG) {
                    log("Adding " + r + " with metric " + metric + " for interface " + ifaceName);
                }
                try {
                     //metric update removes existing route and adds another
                     //with newer metric. So check for duplicate here.
                     if (! mAddedRoutes.contains(r))
                         mAddedRoutes.add(r);  // only track default table
                     if (VDBG) log("Routes in main table - [ " + mAddedRoutes + " ]");
                     mNetd.addRouteWithMetric(ifaceName, metric, r);
                } catch (Exception e) {
                    // never crash - catch them all
                    if (VDBG) loge("Exception trying to add a Metric Route: " + e);
                    return false;
                }
                return true;
            }

            /**
             * Add and remove routes using the old properties (null if not previously connected),
             * new properties (null if becoming disconnected).  May even be double null, which
             * is a noop.
             * Uses isLinkDefault to determine if default routes should be set. Adds host routes
             * to the dns servers for all networks. Adds source policy routes for all networks.
             * Returns a boolean indicating the routes changed
             */
            protected boolean updateRoutes(LinkProperties newLp, LinkProperties curLp,
                        boolean isLinkDefault, RouteAttributes ra) {
                Collection<RouteInfo> routesToAdd = null;
                CompareResult<InetAddress> dnsDiff = new CompareResult<InetAddress>();
                CompareResult<RouteInfo> routeDiff = new CompareResult<RouteInfo>();
                CompareResult<LinkAddress> localAddrDiff = new CompareResult<LinkAddress>();
                if (curLp != null) {
                    // check for the delta between the current set and the new
                    routeDiff = curLp.compareRoutes(newLp);
                    dnsDiff = curLp.compareDnses(newLp);
                        localAddrDiff = curLp.compareAddresses(newLp);
                } else if (newLp != null) {
                    routeDiff.added = newLp.getAllRoutes();
                    dnsDiff.added = newLp.getDnses();
                    localAddrDiff.added = newLp.getLinkAddresses();
                }

                boolean routesChanged = (routeDiff.removed.size() != 0 || routeDiff.added.size() != 0);

                for (RouteInfo r : routeDiff.removed) {
                    if (isLinkDefault || ! r.isDefaultRoute()) {
                        QcConnectivityService.this.removeRoute(curLp, r, TO_DEFAULT_TABLE);
                    }
                    if (isLinkDefault == false) {
                        // remove from a secondary route table
                        QcConnectivityService.this.removeRoute(curLp, r, TO_SECONDARY_TABLE);
                    }
                }

                // handle DNS routes for all net types - no harm done
                if (routesChanged) {
                    // routes changed - remove all old dns entries and add new
                    if (curLp != null) {
                        for (InetAddress oldDns : curLp.getDnses()) {
                            QcConnectivityService.this.removeRouteToAddress(curLp, oldDns);
                        }
                    }
                    if (newLp != null) {
                        for (InetAddress newDns : newLp.getDnses()) {
                            QcConnectivityService.this.addRouteToAddress(newLp, newDns);
                        }
                    }
                } else {
                    // no change in routes, check for change in dns themselves
                    for (InetAddress oldDns : dnsDiff.removed) {
                        QcConnectivityService.this.removeRouteToAddress(curLp, oldDns);
                    }
                    for (InetAddress newDns : dnsDiff.added) {
                        QcConnectivityService.this.addRouteToAddress(newLp, newDns);
                    }
                }

                for (RouteInfo r :  routeDiff.added) {
                    if (isLinkDefault || ! r.isDefaultRoute()) {
                        // add to main table - uses custom addRoute with metric
                        addRoute(newLp, r, 0, ra.getMetric());
                    } else {
                        // add to a secondary route table - uses default addRoute method
                        QcConnectivityService.this.addRoute(newLp, r, TO_SECONDARY_TABLE);

                        // many radios add a default route even when we don't want one.
                        // remove the default route unless somebody else has asked for it
                        String ifaceName = newLp.getInterfaceName();
                        if (TextUtils.isEmpty(ifaceName) == false && mAddedRoutes.contains(r) == false) {
                            if (VDBG) log("Removing " + r + " for interface " + ifaceName);
                            try {
                                mNetd.removeRoute(ifaceName, r);
                            } catch (Exception e) {
                                // never crash - catch them all
                                if (DBG) loge("Exception trying to remove a route: " + e);
                            }
                        }
                    }
                }

                if (localAddrDiff.removed.size() != 0) {
                    for (LinkAddress la : localAddrDiff.removed) {
                        if (VDBG) log("Removing src route for:" + la.getAddress().getHostAddress());
                        try {
                             mNetd.delSrcRoute(la.getAddress().getAddress(), ra.getTableId());
                        } catch (Exception e) {
                            loge("Exception while trying to remove src route: " + e);
                        }
                    }
                }

                if (localAddrDiff.added.size() != 0) {
                    InetAddress gw4Addr = null, gw6Addr = null;
                    String ifaceName = newLp.getInterfaceName();
                    if (! TextUtils.isEmpty(ifaceName)) {
                        for (RouteInfo r : newLp.getRoutes()) {
                            if (! r.isDefaultRoute()) continue;
                            if (r.getGateway() instanceof Inet4Address)
                                gw4Addr = r.getGateway();
                            else
                                gw6Addr = r.getGateway();
                        } //gateway is optional so continue adding the source route.
                        for (LinkAddress la : localAddrDiff.added) {
                            try {
                                if (la.getAddress() instanceof Inet4Address) {
                                    mNetd.replaceSrcRoute(ifaceName, la.getAddress().getAddress(),
                                            gw4Addr.getAddress(), ra.getTableId());
                                } else {
                                    mNetd.replaceSrcRoute(ifaceName, la.getAddress().getAddress(),
                                            gw6Addr.getAddress(), ra.getTableId());
                                }
                            } catch (Exception e) {
                                //never crash, catch them all
                                loge("Exception while trying to add src route: " + e);
                            }
                        }
                    }
                }
                return routesChanged;
            }

        } // end dualConnectivityState class

    } // end ConnectivityServiceHSM

    // javadoc from interface
    @Override
    public int tether(String iface) {
        enforceTetherChangePermission();

        if (isTetheringSupported()) {
            return mTethering.tether(iface);
        } else {
            return ConnectivityManager.TETHER_ERROR_UNSUPPORTED;
        }
    }

    private void handleUpdateBlockedUids(int uid, boolean isBlocked) {
        QcConnectivityService.super.updateBlockedUids(uid, isBlocked);
    }

    // javadoc from interface
    @Override
    public int untether(String iface) {
        enforceTetherChangePermission();

        if (isTetheringSupported()) {
            return mTethering.untether(iface);
        } else {
            return ConnectivityManager.TETHER_ERROR_UNSUPPORTED;
        }
    }

    // javadoc from interface
    @Override
    public int getLastTetherError(String iface) {
        enforceTetherAccessPermission();

        if (isTetheringSupported()) {
            return mTethering.getLastTetherError(iface);
        } else {
            return ConnectivityManager.TETHER_ERROR_UNSUPPORTED;
        }
    }

    // TODO - proper iface API for selection by property, inspection, etc
    @Override
    public String[] getTetherableUsbRegexs() {
        enforceTetherAccessPermission();
        if (isTetheringSupported()) {
            return mTethering.getTetherableUsbRegexs();
        } else {
            return new String[0];
        }
    }

    @Override
    public String[] getTetherableWifiRegexs() {
        enforceTetherAccessPermission();
        if (isTetheringSupported()) {
            return mTethering.getTetherableWifiRegexs();
        } else {
            return new String[0];
        }
    }

    @Override
    public String[] getTetherableBluetoothRegexs() {
        enforceTetherAccessPermission();
        if (isTetheringSupported()) {
            return mTethering.getTetherableBluetoothRegexs();
        } else {
            return new String[0];
        }
    }

    @Override
    public int setUsbTethering(boolean enable) {
        enforceTetherChangePermission();
        if (isTetheringSupported()) {
            return mTethering.setUsbTethering(enable);
        } else {
            return ConnectivityManager.TETHER_ERROR_UNSUPPORTED;
        }
    }

    // TODO - move iface listing, queries, etc to new module
    // javadoc from interface
    @Override
    public String[] getTetherableIfaces() {
        enforceTetherAccessPermission();
        return mTethering.getTetherableIfaces();
    }

    @Override
    public String[] getTetheredIfaces() {
        enforceTetherAccessPermission();
        return mTethering.getTetheredIfaces();
    }

    @Override
    public String[] getTetheredIfacePairs() {
        enforceTetherAccessPermission();
        return mTethering.getTetheredIfacePairs();
    }

    @Override
    public String[] getTetheringErroredIfaces() {
        enforceTetherAccessPermission();
        return mTethering.getErroredIfaces();
    }

    // if ro.tether.denied = true we default to no tethering
    // gservices could set the secure setting to 1 though to enable it on a build where it
    // had previously been turned off.
    @Override
    public boolean isTetheringSupported() {
        enforceTetherAccessPermission();
        int defaultVal = (SystemProperties.get("ro.tether.denied").equals("true") ? 0 : 1);
        boolean tetherEnabledInSettings = (Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.TETHER_SUPPORTED, defaultVal) != 0);
        return tetherEnabledInSettings && mTetheringConfigValid;
    }

    // An API NetworkStateTrackers can call when they lose their network.
    // This will request the HSM to acquire NetTransition wakelock
    @Override
    public void requestNetworkTransitionWakelock(String forWhom) {
        enforceConnectivityInternalPermission();
        synchronized (this) {
        mHandler.sendMessage(mHandler.obtainMessage(
                ConnectivityServiceHSM.HSM_HANDLE_REQUEST_NET_TRANSITION_WAKELOCK,
                forWhom));
        }
        return;
    }

    // 100 percent is full good, 0 is full bad.
    @Override
    public void reportInetCondition(int networkType, int percentage) {
        if (VDBG) log("reportNetworkCondition(" + networkType + ", " + percentage + ")");
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.STATUS_BAR,
                "ConnectivityService");

        if (DBG) {
            int pid = getCallingPid();
            int uid = getCallingUid();
            String s = pid + "(" + uid + ") reports inet is " +
                (percentage > 50 ? "connected" : "disconnected") + " (" + percentage + ") on " +
                "network Type " + networkType + " at " + GregorianCalendar.getInstance().getTime();
            mInetLog.add(s);
            while(mInetLog.size() > INET_CONDITION_LOG_MAX_SIZE) {
                mInetLog.remove(0);
            }
        }
        mHandler.sendMessage(mHandler.obtainMessage(
            EVENT_INET_CONDITION_CHANGE, networkType, percentage));
    }

    private void handleInetConditionChange(int netType, int condition) {
        if (mActiveDefaultNetwork == -1) {
            if (DBG) log("handleInetConditionChange: no active default network - ignore");
            return;
        }
        if (mActiveDefaultNetwork != netType) {
            if (DBG) log("handleInetConditionChange: net=" + netType +
                            " != default=" + mActiveDefaultNetwork + " - ignore");
            return;
        }
        if (VDBG) {
            log("handleInetConditionChange: net=" +
                    netType + ", condition=" + condition +
                    ",mActiveDefaultNetwork=" + mActiveDefaultNetwork);
        }
        mDefaultInetCondition = condition;
        int delay;
        if (mInetConditionChangeInFlight == false) {
            if (VDBG) log("handleInetConditionChange: starting a change hold");
            // setup a new hold to debounce this
            if (mDefaultInetCondition > 50) {
                delay = Settings.Global.getInt(mContext.getContentResolver(),
                        Settings.Global.INET_CONDITION_DEBOUNCE_UP_DELAY, 500);
            } else {
                delay = Settings.Global.getInt(mContext.getContentResolver(),
                        Settings.Global.INET_CONDITION_DEBOUNCE_DOWN_DELAY, 3000);
            }
            mInetConditionChangeInFlight = true;
            mHandler.sendMessageDelayed(mHandler.obtainMessage(EVENT_INET_CONDITION_HOLD_END,
                    mActiveDefaultNetwork, mDefaultConnectionSequence), delay);
        } else {
            // we've set the new condition, when this hold ends that will get picked up
            if (VDBG) log("handleInetConditionChange: currently in hold - not setting new end evt");
        }
    }

    private void handleInetConditionHoldEnd(int netType, int sequence) {
        if (DBG) {
            log("handleInetConditionHoldEnd: net=" + netType +
                    ", condition=" + mDefaultInetCondition +
                    ", published condition=" + mDefaultInetConditionPublished);
        }
        mInetConditionChangeInFlight = false;

        if (mActiveDefaultNetwork == -1) {
            if (DBG) log("handleInetConditionHoldEnd: no active default network - ignoring");
            return;
        }
        if (mDefaultConnectionSequence != sequence) {
            if (DBG) log("handleInetConditionHoldEnd: event hold for obsolete network - ignoring");
            return;
        }
        // TODO: Figure out why this optimization sometimes causes a
        //       change in mDefaultInetCondition to be missed and the
        //       UI to not be updated.
        //if (mDefaultInetConditionPublished == mDefaultInetCondition) {
        //    if (DBG) log("no change in condition - aborting");
        //    return;
        //}
        NetworkInfo networkInfo = mNetTrackers[mActiveDefaultNetwork].getNetworkInfo();
        if (networkInfo.isConnected() == false) {
            if (DBG) log("handleInetConditionHoldEnd: default network not connected - ignoring");
            return;
        }
        mDefaultInetConditionPublished = mDefaultInetCondition;
        sendInetConditionBroadcast(networkInfo);
        return;
    }

    @Override
    public ProxyProperties getProxy() {
        // this information is already available as a world read/writable jvm property
        // so this API change wouldn't have a benifit.  It also breaks the passing
        // of proxy info to all the JVMs.
        // enforceAccessPermission();
        synchronized (mProxyLock) {
            if (mGlobalProxy != null) return mGlobalProxy;
            return (mDefaultProxyDisabled ? null : mDefaultProxy);
        }
    }

    @Override
    public void setGlobalProxy(ProxyProperties proxyProperties) {
        enforceConnectivityInternalPermission();
        synchronized (mProxyLock) {
            if (proxyProperties == mGlobalProxy) return;
            if (proxyProperties != null && proxyProperties.equals(mGlobalProxy)) return;
            if (mGlobalProxy != null && mGlobalProxy.equals(proxyProperties)) return;

            String host = "";
            int port = 0;
            String exclList = "";
            if (proxyProperties != null && !TextUtils.isEmpty(proxyProperties.getHost())) {
                mGlobalProxy = new ProxyProperties(proxyProperties);
                host = mGlobalProxy.getHost();
                port = mGlobalProxy.getPort();
                exclList = mGlobalProxy.getExclusionList();
            } else {
                mGlobalProxy = null;
            }
            ContentResolver res = mContext.getContentResolver();
            final long token = Binder.clearCallingIdentity();
            try {
                Settings.Global.putString(res, Settings.Global.GLOBAL_HTTP_PROXY_HOST, host);
                Settings.Global.putInt(res, Settings.Global.GLOBAL_HTTP_PROXY_PORT, port);
                Settings.Global.putString(res, Settings.Global.GLOBAL_HTTP_PROXY_EXCLUSION_LIST,
                        exclList);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        if (mGlobalProxy == null) {
            proxyProperties = mDefaultProxy;
        }
        sendProxyBroadcast(proxyProperties);
    }

    private void loadGlobalProxy() {
        ContentResolver res = mContext.getContentResolver();
        String host = Settings.Global.getString(res, Settings.Global.GLOBAL_HTTP_PROXY_HOST);
        int port = Settings.Global.getInt(res, Settings.Global.GLOBAL_HTTP_PROXY_PORT, 0);
        String exclList = Settings.Global.getString(res,
                Settings.Global.GLOBAL_HTTP_PROXY_EXCLUSION_LIST);
        if (!TextUtils.isEmpty(host)) {
            ProxyProperties proxyProperties = new ProxyProperties(host, port, exclList);
            synchronized (mProxyLock) {
                mGlobalProxy = proxyProperties;
            }
        }
    }

    @Override
    public ProxyProperties getGlobalProxy() {
        // this information is already available as a world read/writable jvm property
        // so this API change wouldn't have a benifit.  It also breaks the passing
        // of proxy info to all the JVMs.
        // enforceAccessPermission();
        synchronized (mProxyLock) {
            return mGlobalProxy;
        }
    }

    private void handleApplyDefaultProxy(ProxyProperties proxy) {
        if (proxy != null && TextUtils.isEmpty(proxy.getHost())) {
            proxy = null;
        }
        synchronized (mProxyLock) {
            if (mDefaultProxy != null && mDefaultProxy.equals(proxy)) return;
            if (mDefaultProxy == proxy) return; // catches repeated nulls
            mDefaultProxy = proxy;

            if (mGlobalProxy != null) return;
            if (!mDefaultProxyDisabled) {
                sendProxyBroadcast(proxy);
            }
        }
    }

    private void handleDeprecatedGlobalHttpProxy() {
        String proxy = Settings.Global.getString(mContext.getContentResolver(),
                Settings.Global.HTTP_PROXY);
        if (!TextUtils.isEmpty(proxy)) {
            String data[] = proxy.split(":");
            String proxyHost =  data[0];
            int proxyPort = 8080;
            if (data.length > 1) {
                try {
                    proxyPort = Integer.parseInt(data[1]);
                } catch (NumberFormatException e) {
                    return;
                }
            }
            ProxyProperties p = new ProxyProperties(data[0], proxyPort, "");
            setGlobalProxy(p);
        }
    }

    private void sendProxyBroadcast(ProxyProperties proxy) {
        if (proxy == null) proxy = new ProxyProperties("", 0, "");
        if (DBG) log("sending Proxy Broadcast for " + proxy);
        Intent intent = new Intent(Proxy.PROXY_CHANGE_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING |
            Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        intent.putExtra(Proxy.EXTRA_PROXY_INFO, proxy);
        final long ident = Binder.clearCallingIdentity();
        try {
            mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private static class SettingsObserver extends ContentObserver {
        private int mWhat;
        private Handler mHandler;
        SettingsObserver(Handler handler, int what) {
            super(handler);
            mHandler = handler;
            mWhat = what;
        }

        void observe(Context context) {
            ContentResolver resolver = context.getContentResolver();
            resolver.registerContentObserver(Settings.Global.getUriFor(
                    Settings.Global.HTTP_PROXY), false, this);
        }

        @Override
        public void onChange(boolean selfChange) {
            mHandler.obtainMessage(mWhat).sendToTarget();
        }
    }

    private static void log(String s) {
        Slog.d(TAG, s);
    }

    private static void loge(String s) {
        Slog.e(TAG, s);
    }

    private static void loge(String s, Exception e) {
        Slog.e(TAG, s, e);
    }

    private void logw(String s) {
        Slog.w(TAG, s);
    }

    private void logv(String s) {
        Slog.v(TAG, s);
    }

    public int convertFeatureToNetworkType(int networkType, String feature) {
        int usedNetworkType = networkType;

        if(networkType == ConnectivityManager.TYPE_MOBILE) {
            if (TextUtils.equals(feature, Phone.FEATURE_ENABLE_MMS)) {
                usedNetworkType = ConnectivityManager.TYPE_MOBILE_MMS;
            } else if (TextUtils.equals(feature, Phone.FEATURE_ENABLE_SUPL)) {
                usedNetworkType = ConnectivityManager.TYPE_MOBILE_SUPL;
            } else if (TextUtils.equals(feature, Phone.FEATURE_ENABLE_DUN) ||
                    TextUtils.equals(feature, Phone.FEATURE_ENABLE_DUN_ALWAYS)) {
                usedNetworkType = ConnectivityManager.TYPE_MOBILE_DUN;
            } else if (TextUtils.equals(feature, Phone.FEATURE_ENABLE_HIPRI)) {
                usedNetworkType = ConnectivityManager.TYPE_MOBILE_HIPRI;
            } else if (TextUtils.equals(feature, Phone.FEATURE_ENABLE_FOTA)) {
                usedNetworkType = ConnectivityManager.TYPE_MOBILE_FOTA;
            } else if (TextUtils.equals(feature, Phone.FEATURE_ENABLE_IMS)) {
                usedNetworkType = ConnectivityManager.TYPE_MOBILE_IMS;
            } else if (TextUtils.equals(feature, Phone.FEATURE_ENABLE_CBS)) {
                usedNetworkType = ConnectivityManager.TYPE_MOBILE_CBS;
            } else {
                Slog.e(TAG, "Can't match any mobile netTracker!");
            }
        } else if (networkType == ConnectivityManager.TYPE_WIFI) {
            if (TextUtils.equals(feature, "p2p")) {
                usedNetworkType = ConnectivityManager.TYPE_WIFI_P2P;
            } else {
                Slog.e(TAG, "Can't match any wifi netTracker!");
            }
        } else {
            Slog.e(TAG, "Unexpected network type");
        }
        return usedNetworkType;
    }

    private static <T> T checkNotNull(T value, String message) {
        if (value == null) {
            throw new NullPointerException(message);
        }
        return value;
    }

    /**
     * Protect a socket from VPN routing rules. This method is used by
     * VpnBuilder and not available in ConnectivityManager. Permissions
     * are checked in Vpn class.
     * @hide
     */
    @Override
    public boolean protectVpn(ParcelFileDescriptor socket) {
        throwIfLockdownEnabled();
        try {
            int type = mActiveDefaultNetwork;
            if (ConnectivityManager.isNetworkTypeValid(type) && mNetTrackers[type] != null) {
                mVpn.protect(socket, mNetTrackers[type].getLinkProperties().getInterfaceName());
                return true;
            }
        } catch (Exception e) {
            // ignore
        } finally {
            try {
                socket.close();
            } catch (Exception e) {
                // ignore
            }
        }
        return false;
    }

    /**
     * Prepare for a VPN application. This method is used by VpnDialogs
     * and not available in ConnectivityManager. Permissions are checked
     * in Vpn class.
     * @hide
     */
    @Override
    public boolean prepareVpn(String oldPackage, String newPackage) {
        throwIfLockdownEnabled();
        return mVpn.prepare(oldPackage, newPackage);
    }

    /**
     * Configure a TUN interface and return its file descriptor. Parameters
     * are encoded and opaque to this class. This method is used by VpnBuilder
     * and not available in ConnectivityManager. Permissions are checked in
     * Vpn class.
     * @hide
     */
    @Override
    public ParcelFileDescriptor establishVpn(VpnConfig config) {
        throwIfLockdownEnabled();
        return mVpn.establish(config);
    }

    /**
     * Start legacy VPN, controlling native daemons as needed. Creates a
     * secondary thread to perform connection work, returning quickly.
     */
    @Override
    public void startLegacyVpn(VpnProfile profile) {
        throwIfLockdownEnabled();
        final LinkProperties egress = getActiveLinkProperties();
        if (egress == null) {
            throw new IllegalStateException("Missing active network connection");
        }
        mVpn.startLegacyVpn(profile, mKeyStore, egress);
    }

    /**
     * Return the information of the ongoing legacy VPN. This method is used
     * by VpnSettings and not available in ConnectivityManager. Permissions
     * are checked in Vpn class.
     * @hide
     */
    @Override
    public LegacyVpnInfo getLegacyVpnInfo() {
        throwIfLockdownEnabled();
        return mVpn.getLegacyVpnInfo();
    }

    /**
     * Callback for VPN subsystem. Currently VPN is not adapted to the service
     * through NetworkStateTracker since it works differently. For example, it
     * needs to override DNS servers but never takes the default routes. It
     * relies on another data network, and it could keep existing connections
     * alive after reconnecting, switching between networks, or even resuming
     * from deep sleep. Calls from applications should be done synchronously
     * to avoid race conditions. As these are all hidden APIs, refactoring can
     * be done whenever a better abstraction is developed.
     */
    public class VpnCallback extends ConnectivityService.VpnCallback {

        public void onStateChanged(NetworkInfo info) {
            mHandler.obtainMessage(EVENT_VPN_STATE_CHANGED, info).sendToTarget();
        }

        public void override(List<String> dnsServers, List<String> searchDomains) {
            if (dnsServers == null) {
                restore();
                return;
            }

            // Convert DNS servers into addresses.
            List<InetAddress> addresses = new ArrayList<InetAddress>();
            for (String address : dnsServers) {
                // Double check the addresses and remove invalid ones.
                try {
                    addresses.add(InetAddress.parseNumericAddress(address));
                } catch (Exception e) {
                    // ignore
                }
            }
            if (addresses.isEmpty()) {
                restore();
                return;
            }

            // Concatenate search domains into a string.
            StringBuilder buffer = new StringBuilder();
            if (searchDomains != null) {
                for (String domain : searchDomains) {
                    buffer.append(domain).append(' ');
                }
            }
            String domains = buffer.toString().trim();

            // Apply DNS changes.
            synchronized (mDnsLock) {
                updateDnsLocked("VPN", "VPN", addresses, domains);
                mDnsOverridden = true;
            }

            // Temporarily disable the default proxy (not global).
            synchronized (mProxyLock) {
                mDefaultProxyDisabled = true;
                if (mGlobalProxy == null && mDefaultProxy != null) {
                    sendProxyBroadcast(null);
                }
            }

            // TODO: support proxy per network.
        }

        public void restore() {
            synchronized (mDnsLock) {
                if (mDnsOverridden) {
                    mDnsOverridden = false;
                    mHandler.sendEmptyMessage(EVENT_RESTORE_DNS);
                }
            }
            synchronized (mProxyLock) {
                mDefaultProxyDisabled = false;
                if (mGlobalProxy == null && mDefaultProxy != null) {
                    sendProxyBroadcast(mDefaultProxy);
                }
            }
        }
    }

    @Override
    public boolean updateLockdownVpn() {
        if (Binder.getCallingUid() != Process.SYSTEM_UID) {
            Slog.w(TAG, "Lockdown VPN only available to AID_SYSTEM");
            return false;
        }

        // Tear down existing lockdown if profile was removed
        mLockdownEnabled = LockdownVpnTracker.isEnabled();
        if (mLockdownEnabled) {
            if (!mKeyStore.isUnlocked()) {
                Slog.w(TAG, "KeyStore locked; unable to create LockdownTracker");
                return false;
            }

            final String profileName = new String(mKeyStore.get(Credentials.LOCKDOWN_VPN));
            final VpnProfile profile = VpnProfile.decode(
                    profileName, mKeyStore.get(Credentials.VPN + profileName));
            setLockdownTracker(new LockdownVpnTracker(mContext, mNetd, this, mVpn, profile));
        } else {
            setLockdownTracker(null);
        }

        return true;
    }

    /**
     * Internally set new {@link LockdownVpnTracker}, shutting down any existing
     * {@link LockdownVpnTracker}. Can be {@code null} to disable lockdown.
     */
    private void setLockdownTracker(LockdownVpnTracker tracker) {
        // Shutdown any existing tracker
        final LockdownVpnTracker existing = mLockdownTracker;
        mLockdownTracker = null;
        if (existing != null) {
            existing.shutdown();
        }

        try {
            if (tracker != null) {
                mNetd.setFirewallEnabled(true);
                mNetd.setFirewallInterfaceRule("lo", true);
                mLockdownTracker = tracker;
                mLockdownTracker.init();
            } else {
                mNetd.setFirewallEnabled(false);
            }
        } catch (RemoteException e) {
            // ignored; NMS lives inside system_server
        }
    }

    private void throwIfLockdownEnabled() {
        if (mLockdownEnabled) {
            throw new IllegalStateException("Unavailable in lockdown mode");
        }
    }

    @Override
    public void supplyMessenger(int networkType, Messenger messenger) {
        enforceConnectivityInternalPermission();

        if (isNetworkTypeValid(networkType) && mNetTrackers[networkType] != null) {
            mNetTrackers[networkType].supplyMessenger(messenger);
        }
    }

    @Override
    public int findConnectionTypeForIface(String iface) {
        enforceConnectivityInternalPermission();

        if (TextUtils.isEmpty(iface)) return ConnectivityManager.TYPE_NONE;
        for (NetworkStateTracker tracker : mNetTrackers) {
            if (tracker != null) {
                LinkProperties lp = tracker.getLinkProperties();
                if (lp != null && iface.equals(lp.getInterfaceName())) {
                    return tracker.getNetworkInfo().getType();
                }
            }
        }
        return ConnectivityManager.TYPE_NONE;
    }

    public void startCne() {
        try {
            PathClassLoader cneClassLoader =
                new PathClassLoader("/system/framework/com.quicinc.cne.jar",
                                    ClassLoader.getSystemClassLoader());
            Class cneClass = cneClassLoader.loadClass("com.quicinc.cne.CNE");
            Constructor cneConstructor = cneClass.getConstructor(
                    new Class[] {Context.class, Handler.class});
            cneObj = cneConstructor.newInstance(mContext, mHandler);
        } catch (Exception e) {
            cneObj = null;
            loge("Creating Connectivity Engine Service", e);
        }
        try {
            if (cneObj != null && (cneObj instanceof IBinder)) {
                ServiceManager.addService("cneservice", (IBinder)cneObj);
                logw("Created CNESERVICE");
            }
        } catch (Exception e) {
            loge("Creating Connectivity Engine Service", e);
        }
    }

    /** Location to an updatable file listing carrier provisioning urls.
     *  An example:
     *
     * <?xml version="1.0" encoding="utf-8"?>
     *  <provisioningUrls>
     *   <provisioningUrl mcc="310" mnc="4">http://myserver.com/foo?mdn=%3$s&amp;
     *   iccid=%1$s&amp;imei=%2$s</provisioningUrl>
     *   <redirectedUrl mcc="310" mnc="4">http://www.google.com</redirectedUrl>
     *  </provisioningUrls>
     */
    private static final String PROVISIONING_URL_PATH =
            "/data/misc/radio/provisioning_urls.xml";
    private final File mProvisioningUrlFile = new File(PROVISIONING_URL_PATH);

    /** XML tag for root element. */
    private static final String TAG_PROVISIONING_URLS = "provisioningUrls";
    /** XML tag for individual url */
    private static final String TAG_PROVISIONING_URL = "provisioningUrl";
    /** XML tag for redirected url */
    private static final String TAG_REDIRECTED_URL = "redirectedUrl";
    /** XML attribute for mcc */
    private static final String ATTR_MCC = "mcc";
    /** XML attribute for mnc */
    private static final String ATTR_MNC = "mnc";

    private static final int REDIRECTED_PROVISIONING = 1;
    private static final int PROVISIONING = 2;

    private String getProvisioningUrlBaseFromFile(int type) {
        FileReader fileReader = null;
        XmlPullParser parser = null;
        Configuration config = mContext.getResources().getConfiguration();
        String tagType;

        switch (type) {
            case PROVISIONING:
                tagType = TAG_PROVISIONING_URL;
                break;
            case REDIRECTED_PROVISIONING:
                tagType = TAG_REDIRECTED_URL;
                break;
            default:
                throw new RuntimeException("getProvisioningUrlBaseFromFile: Unexpected parameter " +
                        type);
        }

        try {
            fileReader = new FileReader(mProvisioningUrlFile);
            parser = Xml.newPullParser();
            parser.setInput(fileReader);
            XmlUtils.beginDocument(parser, TAG_PROVISIONING_URLS);

            while (true) {
                XmlUtils.nextElement(parser);

                String element = parser.getName();
                if (element == null) break;

                if (element.equals(tagType)) {
                    String mcc = parser.getAttributeValue(null, ATTR_MCC);
                    try {
                        if (mcc != null && Integer.parseInt(mcc) == config.mcc) {
                            String mnc = parser.getAttributeValue(null, ATTR_MNC);
                            if (mnc != null && Integer.parseInt(mnc) == config.mnc) {
                                parser.next();
                                if (parser.getEventType() == XmlPullParser.TEXT) {
                                    return parser.getText();
                                }
                            }
                        }
                    } catch (NumberFormatException e) {
                        loge("NumberFormatException in getProvisioningUrlBaseFromFile: " + e);
                    }
                }
            }
            return null;
        } catch (FileNotFoundException e) {
            loge("Carrier Provisioning Urls file not found");
        } catch (XmlPullParserException e) {
            loge("Xml parser exception reading Carrier Provisioning Urls file: " + e);
        } catch (IOException e) {
            loge("I/O exception reading Carrier Provisioning Urls file: " + e);
        } finally {
            if (fileReader != null) {
                try {
                    fileReader.close();
                } catch (IOException e) {}
            }
        }
        return null;
    }


    @Override
    public String getMobileProvisioningUrl() {
        enforceConnectivityInternalPermission();
        String url = getProvisioningUrlBaseFromFile(PROVISIONING);
        if (TextUtils.isEmpty(url)) {
            url = mContext.getResources().getString(R.string.mobile_provisioning_url);
            log("getProvisioningUrl: mobile_provisioining_url from resource =" + url);
        } else {
            log("getProvisioningUrl: mobile_provisioning_url from File =" + url);
        }
        // populate the iccid, imei and phone number in the provisioning url.
        if (!TextUtils.isEmpty(url)) {
            String phoneNumber = mTelephonyManager.getLine1Number();
            if (TextUtils.isEmpty(phoneNumber)) {
                phoneNumber = "0000000000";
            }
            url = String.format(url,
                    mTelephonyManager.getSimSerialNumber() /* ICCID */,
                    mTelephonyManager.getDeviceId() /* IMEI */,
                    phoneNumber /* Phone numer */);
        }

        return url;
    }
}
