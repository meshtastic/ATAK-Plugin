package com.atakmap.android.meshtastic;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;

import androidx.annotation.NonNull;

import com.atakmap.android.data.URIContentManager;
import com.atakmap.android.dropdown.DropDownMapComponent;
import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.meshtastic.cot.CotEventProcessor;
import com.atakmap.android.meshtastic.plugin.R;
import com.atakmap.android.meshtastic.service.MeshServiceManager;
import com.atakmap.android.meshtastic.util.ChunkManager;
import com.atakmap.android.meshtastic.util.Constants;
import com.atakmap.android.meshtastic.util.NotificationHelper;
import com.atakmap.app.preferences.ToolsPreferenceFragment;
import com.atakmap.comms.CommsMapComponent;
import com.atakmap.comms.CotServiceRemote;
import com.atakmap.coremap.cot.event.CotDetail;
import com.atakmap.coremap.cot.event.CotEvent;
import com.atakmap.coremap.filesystem.FileSystemUtils;
import com.atakmap.coremap.log.Log;
import com.geeksville.mesh.ATAKProtos;
import com.geeksville.mesh.DataPacket;
import com.geeksville.mesh.MessageStatus;
import com.geeksville.mesh.MeshProtos;
import com.geeksville.mesh.MeshUser;
import com.geeksville.mesh.MyNodeInfo;
import com.geeksville.mesh.NodeInfo;
import com.geeksville.mesh.Portnums;
import com.google.protobuf.ByteString;
import com.siemens.ct.exi.core.EXIFactory;
import com.siemens.ct.exi.core.helpers.DefaultEXIFactory;
import com.siemens.ct.exi.main.api.sax.EXIResult;

import org.xml.sax.InputSource;
import org.xml.sax.XMLReader;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.XMLConstants;
import com.atakmap.coremap.xml.XMLUtils;

public class MeshtasticMapComponent extends DropDownMapComponent
        implements CommsMapComponent.PreSendProcessor,
        SharedPreferences.OnSharedPreferenceChangeListener,
        CotServiceRemote.ConnectionListener,
        MeshServiceManager.ConnectionListener {
    
    private static final String TAG = "MeshtasticMapComponent";
    
    // Components
    private Context pluginContext;
    private MeshtasticDropDownReceiver ddr;
    private MeshtasticReceiver mr;
    private final MeshtasticExternalGPS meshtasticExternalGPS;
    private MeshtasticSender meshtasticSender;
    
    // Services and Managers
    private MeshServiceManager meshServiceManager;
    private NotificationHelper notificationHelper;
    private ChunkManager chunkManager;
    private CotEventProcessor cotEventProcessor;
    
    // UI Components
    public static MeshtasticWidget mw;
    
    // Connection state management
    public static volatile MeshServiceManager.ServiceConnectionState mConnectionState = MeshServiceManager.ServiceConnectionState.DISCONNECTED;
    private static final Object CONNECTION_STATE_LOCK = new Object();
    
    // Preferences
    private SharedPreferences prefs;
    private SharedPreferences.Editor editor;
    
    // Thread pool for background operations
    private ExecutorService executorService;
    
    public MeshtasticMapComponent() {
        meshtasticExternalGPS = new MeshtasticExternalGPS(new PositionToNMEAMapper());
        chunkManager = new ChunkManager();
        cotEventProcessor = new CotEventProcessor();
        executorService = Executors.newCachedThreadPool();
    }
    
    @Override
    public void onCotServiceConnected(Bundle bundle) {
        // Implementation if needed
    }
    
    @Override
    public void onCotServiceDisconnected() {
        // Implementation if needed
    }
    
    @Override
    public void onServiceConnected() {
        if (mw != null) {
            mw.setIcon("green");
        }
    }
    
    @Override
    public void onServiceDisconnected() {
        if (mw != null) {
            mw.setIcon("red");
        }
    }
    
    @Override
    public void onCreate(final Context context, Intent intent, MapView view) {
        CommsMapComponent.getInstance().registerPreSendProcessor(this);
        context.setTheme(R.style.ATAKPluginTheme);
        pluginContext = context;
        
        // Initialize helpers
        notificationHelper = NotificationHelper.getInstance(view.getContext());
        meshServiceManager = MeshServiceManager.getInstance(view.getContext());
        meshServiceManager.setConnectionListener(this);
        
        // Setup dropdown receiver
        Log.d(TAG, "registering the plugin filter");
        ddr = new MeshtasticDropDownReceiver(view, context);
        AtakBroadcast.DocumentedIntentFilter ddFilter = new AtakBroadcast.DocumentedIntentFilter();
        ddFilter.addAction(MeshtasticDropDownReceiver.SHOW_PLUGIN);
        registerDropDownReceiver(ddr, ddFilter);
        
        // Setup preferences
        prefs = new ProtectedSharedPreferences(
            PreferenceManager.getDefaultSharedPreferences(MapView.getMapView().getContext())
        );
        editor = prefs.edit();
        editor.putBoolean(Constants.PREF_PLUGIN_FILE_TRANSFER, false);
        editor.putBoolean(Constants.PREF_PLUGIN_CHUNKING, false);
        editor.apply();
        prefs.registerOnSharedPreferenceChangeListener(this);
        
        // Setup GPS if enabled
        int gpsPort = prefs.getInt(Constants.PREF_LISTEN_PORT, Constants.DEFAULT_GPS_PORT);
        boolean shouldUseMeshtasticExternalGPS = prefs.getBoolean(Constants.PREF_PLUGIN_EXTERNAL_GPS, false);
        if (shouldUseMeshtasticExternalGPS) {
            meshtasticExternalGPS.start(gpsPort);
        }
        
        // Setup receiver
        mr = new MeshtasticReceiver(meshtasticExternalGPS);
        IntentFilter intentFilter = getIntentFilter();
        view.getContext().registerReceiver(mr, intentFilter, Context.RECEIVER_EXPORTED);
        
        // Setup CoT service
        CotServiceRemote cotService = new CotServiceRemote();
        cotService.setCotEventListener(mr);
        cotService.connect(this);
        
        // Setup URI content manager
        URIContentManager.getInstance().registerSender(
            meshtasticSender = new MeshtasticSender(view, pluginContext)
        );
        
        // Connect to mesh service
        meshServiceManager.connect();
        
        // Setup widget
        mw = new MeshtasticWidget(context, view);
        
        // Register preferences fragment
        ToolsPreferenceFragment.register(
            new ToolsPreferenceFragment.ToolPreference(
                pluginContext.getString(R.string.preferences_title),
                pluginContext.getString(R.string.preferences_summary),
                pluginContext.getString(R.string.meshtastic_preferences),
                pluginContext.getResources().getDrawable(R.drawable.ic_launcher),
                new PluginPreferencesFragment(pluginContext)
            )
        );
    }
    
    @Override
    protected void onDestroyImpl(Context context, MapView view) {
        // Clean up MeshtasticReceiver resources
        if (mr != null) {
            mr.cleanup();
        }
        
        meshServiceManager.disconnect();
        view.getContext().unregisterReceiver(mr);
        if (mw != null) {
            mw.destroy();
        }
        prefs.unregisterOnSharedPreferenceChangeListener(this);
        ToolsPreferenceFragment.unregister(pluginContext.getString(R.string.meshtastic_preferences));
        URIContentManager.getInstance().unregisterSender(meshtasticSender);
        meshtasticExternalGPS.stop();
        
        // Shutdown executor service
        if (executorService != null) {
            executorService.shutdown();
        }
    }

    @Override
    public void processCotEvent(CotEvent cotEvent, String[] strings) {
        Log.d(TAG, "processCotEvent");
        
        // Check if this is a Meshtastic message (don't forward back)
        CotDetail cotDetail = cotEvent.getDetail();
        if (cotDetail != null && cotDetail.getChild("__meshtastic") != null) {
            Log.d(TAG, "Meshtastic message, don't forward");
            return;
        }
        
        // Check service connection
        if (!meshServiceManager.isConnected()) {
            Log.d(TAG, "Service not connected");
            return;
        }
        
        // Check if file transfer or chunking in progress
        if (prefs.getBoolean(Constants.PREF_PLUGIN_FILE_TRANSFER, false)) {
            Log.d(TAG, "File transfer in progress");
            return;
        }
        if (prefs.getBoolean(Constants.PREF_PLUGIN_CHUNKING, false)) {
            Log.d(TAG, "Chunking in progress");
            return;
        }
        
        int hopLimit = MeshtasticReceiver.getHopLimit();
        int channel = MeshtasticReceiver.getChannelIndex();
        
        Log.d(TAG, cotEvent.toString());
        if (cotDetail != null) {
            Log.d(TAG, cotDetail.toString());
        }
        
        // Parse the CoT event
        CotEventProcessor.ParsedCotData parsedData = cotEventProcessor.parseCotEvent(cotEvent);
        
        // Handle different event types
        String uid = cotEvent.getUID();
        String type = cotEvent.getType();
        
        if (uid.equals(MapView.getMapView().getSelfMarker().getUID())) {
            // Self PLI report
            handleSelfPLI(parsedData, hopLimit, channel);
        } else if (type.equalsIgnoreCase("b-t-f") && uid.contains("All Chat Rooms")) {
            // All Chat Rooms message
            handleAllChatMessage(parsedData, hopLimit, channel);
        } else if (type.equalsIgnoreCase("b-t-f")) {
            // Direct message chat
            handleDirectMessage(parsedData, hopLimit, channel);
        } else {
            // Other CoT events
            if (prefs.getBoolean(Constants.PREF_PLUGIN_PLICHAT_ONLY, false)) {
                Log.d(TAG, "PLI/Chat Only mode - ignoring other events");
                return;
            }
            handleGenericCotEvent(cotEvent, hopLimit, channel);
        }
    }
    
    private void handleSelfPLI(CotEventProcessor.ParsedCotData data, int hopLimit, int channel) {
        Log.d(TAG, "Sending self marker PLI to Meshtastic");
        
        ATAKProtos.TAKPacket takPacket = cotEventProcessor.buildPLIPacket(data);
        
        Log.d(TAG, "Total wire size for TAKPacket: " + takPacket.toByteArray().length);
        Log.d(TAG, "Sending: " + takPacket.toString());
        
        DataPacket dp = new DataPacket(
            DataPacket.ID_BROADCAST,
            takPacket.toByteArray(),
            Portnums.PortNum.ATAK_PLUGIN_VALUE,
            DataPacket.ID_LOCAL,
            System.currentTimeMillis(),
            0,
            MessageStatus.UNKNOWN,
            hopLimit,
            channel,
            MeshtasticReceiver.getWantsAck()
        );
        
        meshServiceManager.sendToMesh(dp);
    }
    
    private void handleAllChatMessage(CotEventProcessor.ParsedCotData data, int hopLimit, int channel) {
        Log.d(TAG, "Sending All Chat Rooms to Meshtastic");
        
        ATAKProtos.TAKPacket takPacket = cotEventProcessor.buildChatPacket(data);
        
        Log.d(TAG, "Total wire size for TAKPacket: " + takPacket.toByteArray().length);
        Log.d(TAG, "Sending: " + takPacket.toString());
        
        DataPacket dp = new DataPacket(
            DataPacket.ID_BROADCAST,
            takPacket.toByteArray(),
            Portnums.PortNum.ATAK_PLUGIN_VALUE,
            DataPacket.ID_LOCAL,
            System.currentTimeMillis(),
            0,
            MessageStatus.UNKNOWN,
            hopLimit,
            channel,
            MeshtasticReceiver.getWantsAck()
        );
        
        meshServiceManager.sendToMesh(dp);
    }
    
    private void handleDirectMessage(CotEventProcessor.ParsedCotData data, int hopLimit, int channel) {
        Log.d(TAG, "Sending DM Chat to Meshtastic");
        
        if (data.to == null) {
            return;
        }
        
        DataPacket dp;
        if (data.to.startsWith("!")) {
            // Meshtastic ID - send as text message
            Log.d(TAG, "Sending to Meshtastic ID: " + data.to);
            dp = new DataPacket(
                data.to,
                MeshProtos.Data.newBuilder()
                    .setPayload(ByteString.copyFrom(data.message.getBytes(StandardCharsets.UTF_8)))
                    .build()
                    .toByteArray(),
                Portnums.PortNum.TEXT_MESSAGE_APP_VALUE,
                DataPacket.ID_LOCAL,
                System.currentTimeMillis(),
                0,
                MessageStatus.UNKNOWN,
                hopLimit,
                channel,
                MeshtasticReceiver.getWantsAck()
            );
        } else {
            // Regular ATAK device
            ATAKProtos.TAKPacket takPacket = cotEventProcessor.buildChatPacket(data);
            
            Log.d(TAG, "Total wire size for TAKPacket: " + takPacket.toByteArray().length);
            Log.d(TAG, "Sending: " + takPacket.toString());
            
            dp = new DataPacket(
                DataPacket.ID_BROADCAST,
                takPacket.toByteArray(),
                Portnums.PortNum.ATAK_PLUGIN_VALUE,
                DataPacket.ID_LOCAL,
                System.currentTimeMillis(),
                0,
                MessageStatus.UNKNOWN,
                hopLimit,
                channel,
                MeshtasticReceiver.getWantsAck()
            );
        }
        
        meshServiceManager.sendToMesh(dp);
    }
    
    private void handleGenericCotEvent(CotEvent cotEvent, int hopLimit, int channel) {
        if (prefs.getBoolean(Constants.PREF_PLUGIN_CHUNKING, false)) {
            Log.d(TAG, "Chunking already in progress");
            return;
        }
        
        executorService.execute(() -> {
            Log.d(TAG, "Sending Chunks");
            
            byte[] cotAsBytes;
            try {
                // Compress CoT to EXI format
                EXIFactory exiFactory = DefaultEXIFactory.newInstance();
                ByteArrayOutputStream osEXI = new ByteArrayOutputStream();
                EXIResult exiResult = new EXIResult(exiFactory);
                exiResult.setOutputStream(osEXI);
                
                SAXParserFactory saxParserFactory = SAXParserFactory.newInstance();
                try {
                    saxParserFactory.setFeature("http://xml.org/sax/features/external-general-entities", false);
                    saxParserFactory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
                    saxParserFactory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                    saxParserFactory.setFeature("http://xml.org/sax/features/validation", false);
                    saxParserFactory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
                } catch (Exception e) {
                    Log.w(TAG, "Failed to configure secure SAXParserFactory", e);
                }
                SAXParser newSAXParser = saxParserFactory.newSAXParser();
                XMLReader xmlReader = newSAXParser.getXMLReader();
                xmlReader.setContentHandler(exiResult.getHandler());
                
                InputSource stream = new InputSource(new StringReader(cotEvent.toString()));
                xmlReader.parse(stream);
                cotAsBytes = osEXI.toByteArray();
                osEXI.close();
            } catch (Exception e) {
                Log.e(TAG, "Failed to compress CoT event", e);
                return;
            }
            
            Log.d(TAG, "Size: " + cotAsBytes.length);
            
            // Small messages can be sent directly
            if (cotAsBytes.length < 236) {
                Log.d(TAG, "Small send");
                DataPacket dp = new DataPacket(
                    DataPacket.ID_BROADCAST,
                    cotAsBytes,
                    Portnums.PortNum.ATAK_FORWARDER_VALUE,
                    DataPacket.ID_LOCAL,
                    System.currentTimeMillis(),
                    0,
                    MessageStatus.UNKNOWN,
                    hopLimit,
                    channel,
                    MeshtasticReceiver.getWantsAck()
                );
                meshServiceManager.sendToMesh(dp);
                return;
            }
            
            // Large messages need chunking
            editor.putBoolean(Constants.PREF_PLUGIN_CHUNKING, true);
            editor.apply();
            
            try {
                boolean success = chunkManager.sendChunkedData(
                    cotAsBytes,
                    meshServiceManager.getService(),
                    prefs,
                    hopLimit,
                    channel
                );
                
                if (!success) {
                    Log.e(TAG, "Failed to send chunked data");
                }
            } catch (Exception e) {
                Log.e(TAG, "Chunking failed", e);
            } finally {
                editor.putBoolean(Constants.PREF_PLUGIN_CHUNKING, false);
                editor.apply();
            }
        });
    }
    
    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (key == null) return;
        
        if (FileSystemUtils.isEquals(key, Constants.PREF_PLUGIN_RATE_VALUE)) {
            String rate = prefs.getString(Constants.PREF_PLUGIN_RATE_VALUE, "0");
            Log.d(TAG, "Rate: " + rate);
            editor.putString("locationReportingStrategy", "Constant");
            editor.putString("constantReportingRateUnreliable", rate);
            editor.putString("constantReportingRateReliable", rate);
            editor.apply();
        }
        
        if (Constants.PREF_PLUGIN_EXTERNAL_GPS.equals(key)) {
            boolean shouldUseMeshtasticExternalGPS = prefs.getBoolean(Constants.PREF_PLUGIN_EXTERNAL_GPS, false);
            if (shouldUseMeshtasticExternalGPS) {
                int gpsPort = prefs.getInt(Constants.PREF_LISTEN_PORT, Constants.DEFAULT_GPS_PORT);
                meshtasticExternalGPS.start(gpsPort);
            } else {
                meshtasticExternalGPS.stop();
            }
        }
    }
    
    @NonNull
    private static IntentFilter getIntentFilter() {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Constants.ACTION_RECEIVED_ATAK_FORWARDER);
        intentFilter.addAction("com.geeksville.mesh.RECEIVED.257");
        intentFilter.addAction(Constants.ACTION_RECEIVED_ATAK_PLUGIN);
        intentFilter.addAction("com.geeksville.mesh.RECEIVED.72");
        intentFilter.addAction(Constants.ACTION_NODE_CHANGE);
        intentFilter.addAction(Constants.ACTION_MESH_CONNECTED);
        intentFilter.addAction(Constants.ACTION_MESH_DISCONNECTED);
        intentFilter.addAction(Constants.ACTION_RECEIVED_NODEINFO_APP);
        intentFilter.addAction(Constants.ACTION_RECEIVED_POSITION_APP);
        intentFilter.addAction(Constants.ACTION_MESSAGE_STATUS);
        intentFilter.addAction(Constants.ACTION_TEXT_MESSAGE_APP);
        return intentFilter;
    }
    
    // Static helper methods for backward compatibility
    public static void sendToMesh(DataPacket dp) {
        MeshServiceManager manager = MeshServiceManager.getInstance(MapView.getMapView().getContext());
        manager.sendToMesh(dp);
    }
    
    public static boolean sendFile(File f) {
        try {
            byte[] fileBytes = FileSystemUtils.read(f);
            ChunkManager chunkManager = new ChunkManager();
            MeshServiceManager manager = MeshServiceManager.getInstance(MapView.getMapView().getContext());
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(MapView.getMapView().getContext());
            
            NotificationHelper notificationHelper = NotificationHelper.getInstance(MapView.getMapView().getContext());
            
            boolean success = chunkManager.sendChunkedData(
                fileBytes,
                manager.getService(),
                prefs,
                MeshtasticReceiver.getHopLimit(),
                MeshtasticReceiver.getChannelIndex()
            );
            
            if (success) {
                notificationHelper.showCompletionNotification();
            }
            
            return success;
        } catch (Exception e) {
            Log.e(TAG, "Failed to send file", e);
            return false;
        }
    }
    
    public static void setOwner(MeshUser meshUser) {
        MeshServiceManager manager = MeshServiceManager.getInstance(MapView.getMapView().getContext());
        manager.setOwner(meshUser);
    }
    
    public static void setChannel(byte[] channel) {
        MeshServiceManager manager = MeshServiceManager.getInstance(MapView.getMapView().getContext());
        manager.setChannel(channel);
    }
    
    public static void setConfig(byte[] config) {
        MeshServiceManager manager = MeshServiceManager.getInstance(MapView.getMapView().getContext());
        manager.setConfig(config);
    }
    
    public static byte[] getChannelSet() {
        MeshServiceManager manager = MeshServiceManager.getInstance(MapView.getMapView().getContext());
        return manager.getChannelSet();
    }
    
    public static byte[] getConfig() {
        MeshServiceManager manager = MeshServiceManager.getInstance(MapView.getMapView().getContext());
        return manager.getConfig();
    }
    
    public static List<NodeInfo> getNodes() {
        MeshServiceManager manager = MeshServiceManager.getInstance(MapView.getMapView().getContext());
        return manager.getNodes();
    }
    
    public static MyNodeInfo getMyNodeInfo() {
        MeshServiceManager manager = MeshServiceManager.getInstance(MapView.getMapView().getContext());
        return manager.getMyNodeInfo();
    }
    
    public static String getMyNodeID() {
        MeshServiceManager manager = MeshServiceManager.getInstance(MapView.getMapView().getContext());
        return manager.getMyNodeID();
    }
    
    public static boolean reconnect() {
        MeshServiceManager manager = MeshServiceManager.getInstance(MapView.getMapView().getContext());
        return manager.reconnect();
    }
    
    public static MeshServiceManager getMeshService() {
        return MeshServiceManager.getInstance(MapView.getMapView().getContext());
    }
    
    // Re-export ServiceConnectionState for compatibility
    public static class ServiceConnectionState {
        public static final MeshServiceManager.ServiceConnectionState CONNECTED = MeshServiceManager.ServiceConnectionState.CONNECTED;
        public static final MeshServiceManager.ServiceConnectionState DISCONNECTED = MeshServiceManager.ServiceConnectionState.DISCONNECTED;
    }
}