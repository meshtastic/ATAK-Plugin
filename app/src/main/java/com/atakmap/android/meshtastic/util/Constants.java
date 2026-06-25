package com.atakmap.android.meshtastic.util;

public final class Constants {
    private Constants() {}

    // Service Connection.
    // PACKAGE_NAME stays at com.geeksville.mesh — that's the Meshtastic
    // Android app's applicationId (see the app's build.gradle), unchanged.
    // CLASS_NAME tracks the foreground service class. In Meshtastic Android
    // v2.7.14 the service class was repackaged from
    // com.geeksville.mesh.service.MeshService to
    // org.meshtastic.core.service.MeshService as part of the multi-module
    // KMP refactor. The plugin's MeshServiceManager already imports the new
    // org.meshtastic.core.service.IMeshService AIDL stub; the bind-target
    // class name needed to match. Without this fix, bindService() silently
    // fails to resolve the component on Meshtastic Android 2.7.14+.
    // See: https://github.com/meshtastic/ATAK-Plugin/issues/111
    public static final String PACKAGE_NAME = "com.geeksville.mesh";
    public static final String CLASS_NAME = "org.meshtastic.core.service.MeshService";
    
    // Actions
    public static final String ACTION_MESH_CONNECTED = "com.geeksville.mesh.MESH_CONNECTED";
    public static final String ACTION_MESH_DISCONNECTED = "com.geeksville.mesh.MESH_DISCONNECTED";
    public static final String ACTION_RECEIVED_AUDIO_APP = "com.geeksville.mesh.RECEIVED.AUDIO_APP";
    public static final String ACTION_RECEIVED_ATAK_FORWARDER = "com.geeksville.mesh.RECEIVED.ATAK_FORWARDER";
    public static final String ACTION_RECEIVED_ATAK_PLUGIN = "com.geeksville.mesh.RECEIVED.ATAK_PLUGIN";
    public static final String ACTION_RECEIVED_NODEINFO_APP = "com.geeksville.mesh.RECEIVED.NODEINFO_APP";
    public static final String ACTION_RECEIVED_POSITION_APP = "com.geeksville.mesh.RECEIVED.POSITION_APP";
    public static final String ACTION_TEXT_MESSAGE_APP = "com.geeksville.mesh.RECEIVED.TEXT_MESSAGE_APP";
    public static final String ACTION_ALERT_APP = "com.geeksville.mesh.RECEIVED.ALERT_APP";
    public static final String ACTION_NODE_CHANGE = "com.geeksville.mesh.NODE_CHANGE";
    public static final String ACTION_MESSAGE_STATUS = "com.geeksville.mesh.MESSAGE_STATUS";

    // Extras
    public static final String EXTRA_CONNECTED = "com.geeksville.mesh.Connected";
    public static final String EXTRA_DISCONNECTED = "com.geeksville.mesh.disconnected";
    public static final String EXTRA_PERMANENT = "com.geeksville.mesh.Permanent";
    public static final String EXTRA_PAYLOAD = "com.geeksville.mesh.Payload";
    public static final String EXTRA_NODEINFO = "com.geeksville.mesh.NodeInfo";
    public static final String EXTRA_PACKET_ID = "com.geeksville.mesh.PacketId";
    public static final String EXTRA_STATUS = "com.geeksville.mesh.Status";
    
    // States
    public static final String STATE_CONNECTED = "CONNECTED";
    public static final String STATE_DISCONNECTED = "DISCONNECTED";
    
    // Notification
    public static final String NOTIFICATION_CHANNEL_ID = "com.atakmap.android.meshtastic";
    public static final String NOTIFICATION_CHANNEL_NAME = "Meshtastic Notifications";
    public static final int NOTIFICATION_ID = 42069;
    
    // Transfer Types (prefixed to fountain code payloads)
    public static final byte TRANSFER_TYPE_COT = 0x00;
    public static final byte TRANSFER_TYPE_FILE = 0x01;
    // iOS uses ASCII characters instead of byte values
    public static final byte TRANSFER_TYPE_COT_ASCII = 0x30;  // '0'
    public static final byte TRANSFER_TYPE_FILE_ASCII = 0x31; // '1'
    
    // Audio
    public static final int AUDIO_SAMPLE_RATE = 8000;
    
    // GPS
    public static final int DEFAULT_GPS_PORT = 4349;
    public static final double GPS_COORD_DIVISOR = 1e-7;
    
    // Preferences Keys
    public static final String PREF_PLI_RATE_ENABLED = "plugin_meshtastic_pli_rate_limit";
    public static final String PREF_PLI_RATE_VALUE = "plugin_meshtastic_pli_rate_limit_value";
    public static final String PREF_PLUGIN_RATE_VALUE = "plugin_meshtastic_rate_value";
    public static final String PREF_PLUGIN_EXTERNAL_GPS = "plugin_meshtastic_external_gps";
    public static final String PREF_PLUGIN_CHUNKING = "plugin_meshtastic_chunking";
    public static final String PREF_PLUGIN_PLICHAT_ONLY = "plugin_meshtastic_plichat_only";
    public static final String PREF_PLUGIN_VOICE = "plugin_meshtastic_voice";
    public static final String PREF_PLUGIN_NOGPS = "plugin_meshtastic_nogps";
    public static final String PREF_PLUGIN_SELF = "plugin_meshtastic_self";
    public static final String PREF_PLUGIN_TRACKER = "plugin_meshtastic_tracker";
    public static final String PREF_PLUGIN_SERVER = "plugin_meshtastic_server";
    public static final String PREF_LISTEN_PORT = "listenPort";
    public static final String PREF_PLUGIN_PTT = "plugin_meshtastic_ptt";
    public static final String PREF_PLUGIN_WANT_ACK = "plugin_meshtastic_wantAck";
    public static final String PREF_PLUGIN_HOP_LIMIT = "plugin_meshtastic_hop_limit";
    public static final String PREF_PLUGIN_CHANNEL = "plugin_meshtastic_channel";
    public static final String PREF_PLUGIN_FROM_SERVER = "plugin_meshtastic_from_server";
    public static final String PREF_PLUGIN_FILTER_BY_CHANNEL = "plugin_meshtastic_channel_filter";
    public static final String PREF_PLUGIN_FILE_TRANSFER = "plugin_meshtastic_file_transfer";
    public static final String PREF_PLUGIN_LOCAL_NODE_ID = "plugin_meshtastic_local_node_id";
    public static final String PREF_PLUGIN_EXTRA_ENCRYPTION = "plugin_meshtastic_extra_encryption";
    public static final String PREF_PLUGIN_ENCRYPTION_PSK = "plugin_meshtastic_encryption_psk";
    public static final String PREF_PLUGIN_CHAT_RECEIPTS = "plugin_meshtastic_chat_receipts";
    public static final String PREF_PLUGIN_PTT_MOMENTARY = "plugin_meshtastic_ptt_momentary";
    public static final String PREF_PLUGIN_TOT = "plugin_meshtastic_tot";

    // Encrypted message prefix (to identify encrypted payloads)
    public static final byte ENCRYPTED_MESSAGE_MARKER = (byte) 0xEE;


    // ATAK Intent
    public static final String ATAK_PACKAGE = "com.atakmap.app.civ";
    public static final String ATAK_ACTIVITY = "com.atakmap.app.ATAKActivity";
    public static final String MESHTASTIC_SHOW_PLUGIN = "com.atakmap.android.meshtastic.SHOW_PLUGIN";
}