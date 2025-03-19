package com.atakmap.android.meshtastic;

import static android.content.Context.NOTIFICATION_SERVICE;

import static com.atakmap.android.maps.MapView.*;

import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Environment;
import android.os.RemoteException;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.speech.tts.TextToSpeech;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;

import com.atakmap.android.contact.Contact;
import com.atakmap.android.contact.Contacts;
import com.atakmap.android.cot.CotMapComponent;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.meshtastic.plugin.R;
import com.atakmap.comms.CotServiceRemote;
import com.atakmap.coremap.cot.event.CotDetail;
import com.atakmap.coremap.cot.event.CotEvent;
import com.atakmap.coremap.cot.event.CotPoint;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.time.CoordinatedTime;

import com.geeksville.mesh.ATAKProtos;
import com.geeksville.mesh.ConfigProtos;
import com.geeksville.mesh.DataPacket;

import com.geeksville.mesh.LocalOnlyProtos;
import com.geeksville.mesh.MessageStatus;
import com.geeksville.mesh.NodeInfo;
import com.geeksville.mesh.Portnums;
import com.google.protobuf.InvalidProtocolBufferException;

import java.io.ByteArrayInputStream;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.siemens.ct.exi.core.EXIFactory;
import com.siemens.ct.exi.core.helpers.DefaultEXIFactory;
import com.siemens.ct.exi.main.api.sax.EXISource;

import org.xml.sax.InputSource;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import javax.xml.transform.Result;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.sax.SAXSource;
import javax.xml.transform.stream.StreamResult;

public class MeshtasticReceiver extends BroadcastReceiver implements CotServiceRemote.CotEventListener {
    private static final String TAG = "MeshtasticReceiver";
    private static SharedPreferences prefs;
    private SharedPreferences.Editor editor;
    private int oldModemPreset;
    private String sender;
    private static NotificationManager mNotifyManager;
    private static NotificationCompat.Builder mBuilder;
    private static NotificationChannel mChannel;
    private static int id = 42069;
    @Override
    public void onReceive(Context context, Intent intent) {

        if (mNotifyManager == null) {
            mNotifyManager =
                    (NotificationManager) getMapView().getContext()
                            .getSystemService(NOTIFICATION_SERVICE);
            mChannel = new NotificationChannel(
                    "com.atakmap.android.meshtastic",
                    "Meshtastic Notifications",
                    NotificationManager.IMPORTANCE_DEFAULT); // correct Constant
            mChannel.setSound(null, null);
            mNotifyManager.createNotificationChannel(mChannel);

            Intent atakFrontIntent = new Intent();
            atakFrontIntent.setComponent(new ComponentName(
                    "com.atakmap.app.civ", "com.atakmap.app.ATAKActivity"));
            atakFrontIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            atakFrontIntent.putExtra("internalIntent",
                    new Intent("com.atakmap.android.meshtastic.SHOW_PLUGIN"));
            PendingIntent appIntent = PendingIntent.getActivity(getMapView().getContext(), 0, atakFrontIntent, PendingIntent.FLAG_IMMUTABLE);

            mBuilder = new NotificationCompat.Builder(context, "com.atakmap.android.meshtastic");
            mBuilder.setContentTitle("Meshtastic File Transfer")
                    .setContentText("Transfer in progress")
                    .setSmallIcon(R.drawable.ic_launcher)
                    .setAutoCancel(true)
                    .setOngoing(false)
                    .setContentIntent(appIntent);
        }

        prefs = new ProtectedSharedPreferences(PreferenceManager.getDefaultSharedPreferences(MapView.getMapView().getContext()));
        editor = prefs.edit();

        String action = intent.getAction();
        if (action == null) return;
        Log.d(TAG, "ACTION: " + action);
        switch (action) {
            case MeshtasticMapComponent.ACTION_MESH_CONNECTED: {
                String extraConnected = intent.getStringExtra(MeshtasticMapComponent.EXTRA_CONNECTED);
                boolean connected = extraConnected.equals(MeshtasticMapComponent.STATE_CONNECTED);
                Log.d(TAG, "Received ACTION_MESH_CONNECTED: " + extraConnected);
                if (connected) {
                    try {
                        SharedPreferences.Editor editor = prefs.edit();
                        editor.putBoolean("plugin_meshtastic_shortfast", false);
                        editor.apply();
                        boolean ret = MeshtasticMapComponent.reconnect();
                        if (ret) {
                            MeshtasticMapComponent.mConnectionState = MeshtasticMapComponent.ServiceConnectionState.CONNECTED;
                            MeshtasticMapComponent.mw.setIcon("green");
                        }
                    } catch (RemoteException e) {
                        e.printStackTrace();
                    }
                } else {
                    MeshtasticMapComponent.mConnectionState = MeshtasticMapComponent.ServiceConnectionState.DISCONNECTED;
                    MeshtasticMapComponent.mw.setIcon("red");
                }
                break;
            }
            case MeshtasticMapComponent.ACTION_MESH_DISCONNECTED: {
                String extraConnected = intent.getStringExtra(MeshtasticMapComponent.EXTRA_DISCONNECTED);
                if (extraConnected == null) {
                    Log.d(TAG, "Received ACTION_MESH_DISCONNECTED: null");
                    return;
                }
                boolean connected = extraConnected.equals(MeshtasticMapComponent.STATE_DISCONNECTED);
                Log.d(TAG, "Received ACTION_MESH_DISCONNECTED: " + extraConnected);
                if (connected) {
                    MeshtasticMapComponent.mConnectionState = MeshtasticMapComponent.ServiceConnectionState.DISCONNECTED;
                    MeshtasticMapComponent.mw.setIcon("red");
                }
                break;
            }
            case MeshtasticMapComponent.ACTION_MESSAGE_STATUS:
                int id = intent.getIntExtra(MeshtasticMapComponent.EXTRA_PACKET_ID, 0);
                MessageStatus status = intent.getParcelableExtra(MeshtasticMapComponent.EXTRA_STATUS);
                Log.d(TAG, "Message Status ID: " + id + " Status: " + status);
                if (prefs.getInt("plugin_meshtastic_switch_id", 0) == id && status == MessageStatus.DELIVERED) {
                    editor.putBoolean("plugin_meshtastic_switch_ACK", false);
                    editor.apply();
                    Log.d(TAG, "Got ACK from Switch");
                } else if (prefs.getInt("plugin_meshtastic_chunk_id", 0) == id && status == MessageStatus.DELIVERED) {
                    // clear the ACK/ERR for the chunk
                    editor.putBoolean("plugin_meshtastic_chunk_ACK", false);
                    editor.putBoolean("plugin_meshtastic_chunk_ERR", false);
                    editor.apply();
                    Log.d(TAG, "Got DELIVERED from Chunk");
                } else if (prefs.getInt("plugin_meshtastic_chunk_id", 0) == id && status == MessageStatus.ERROR) {
                    editor.putBoolean("plugin_meshtastic_chunk_ACK", false);
                    editor.putBoolean("plugin_meshtastic_chunk_ERR", true);
                    editor.apply();
                    Log.d(TAG, "Got ERROR from Chunk");
                }
                break;
            case MeshtasticMapComponent.ACTION_RECEIVED_ATAK_FORWARDER:
            case MeshtasticMapComponent.ACTION_RECEIVED_ATAK_PLUGIN: {
                Thread thread = new Thread(() -> {
                    try {
                        receive(intent);
                    } catch (InvalidProtocolBufferException e) {
                        e.printStackTrace();
                        return;
                    }
                });
                thread.setName("MeshtasticReceiver.Worker");
                thread.start();
                break;
            }
            case MeshtasticMapComponent.ACTION_ALERT_APP:
            case MeshtasticMapComponent.ACTION_TEXT_MESSAGE_APP:
                Log.d(TAG, "Got a meshtastic text message");
                DataPacket payload = intent.getParcelableExtra(MeshtasticMapComponent.EXTRA_PAYLOAD);
                if (payload == null) return;
                String message = new String(payload.getBytes());
                Log.d(TAG, "Message: " + message);
                Log.d(TAG, payload.toString());

                if (prefs.getBoolean("plugin_meshtastic_voice", false)) {
                    MeshtasticDropDownReceiver.t1.speak(message, TextToSpeech.QUEUE_FLUSH, null);
                }

                String myNodeID = MeshtasticMapComponent.getMyNodeID();
                if (myNodeID == null) return;

                if (myNodeID.equals(payload.getFrom())) {
                    Log.d(TAG, "Ignoring message from self");
                    return;
                }

                if (payload.getTo().equals("^all")) {
                    Log.d(TAG, "Sending CoT for Text Message");
                    CotEvent cotEvent = new CotEvent();
                    CoordinatedTime time = new CoordinatedTime();
                    cotEvent.setTime(time);
                    cotEvent.setStart(time);
                    cotEvent.setStale(time.addMinutes(10));

                    cotEvent.setUID("GeoChat." + payload.getFrom() + ".All Chat Rooms." + UUID.randomUUID());
                    CotPoint gp = new CotPoint(0, 0, 0, 0, 0);
                    cotEvent.setPoint(gp);
                    cotEvent.setHow("m-g");
                    cotEvent.setType("b-t-f");

                    CotDetail cotDetail = new CotDetail("detail");
                    cotEvent.setDetail(cotDetail);

                    CotDetail chatDetail = new CotDetail("__chat");
                    chatDetail.setAttribute("parent", "RootContactGroup");
                    chatDetail.setAttribute("groupOwner", "false");
                    chatDetail.setAttribute("messageId", UUID.randomUUID().toString());
                    chatDetail.setAttribute("chatroom", "All Chat Rooms");
                    chatDetail.setAttribute("id", "All Chat Rooms");
                    chatDetail.setAttribute("senderCallsign", payload.getFrom());
                    cotDetail.addChild(chatDetail);

                    CotDetail chatgrp = new CotDetail("chatgrp");
                    chatgrp.setAttribute("uid0", payload.getFrom());
                    chatgrp.setAttribute("uid1", "All Chat Rooms");
                    chatgrp.setAttribute("id", "All Chat Rooms");
                    chatDetail.addChild(chatgrp);

                    CotDetail linkDetail = new CotDetail("link");
                    linkDetail.setAttribute("uid", payload.getFrom());
                    linkDetail.setAttribute("type", "a-f-G-U-C");
                    linkDetail.setAttribute("relation", "p-p");
                    cotDetail.addChild(linkDetail);

                    CotDetail serverDestinationDetail = new CotDetail("__serverdestination");
                    serverDestinationDetail.setAttribute("destination", "0.0.0.0:4242:tcp");
                    cotDetail.addChild(serverDestinationDetail);

                    CotDetail remarksDetail = new CotDetail("remarks");
                    remarksDetail.setAttribute("source", "BAO.F.ATAK." + payload.getFrom());
                    remarksDetail.setAttribute("to", "All Chat Rooms");
                    remarksDetail.setAttribute("time", time.toString());
                    remarksDetail.setInnerText(new String(payload.getBytes()));
                    cotDetail.addChild(remarksDetail);

                    if (cotEvent.isValid()) {
                        CotMapComponent.getInternalDispatcher().dispatch(cotEvent);
                        if (prefs.getBoolean("plugin_meshtastic_server", false)) {
                            CotMapComponent.getExternalDispatcher().dispatch(cotEvent);
                        }
                    }
                }

                if (myNodeID.equals(payload.getTo())) {
                    Log.d(TAG, "Sending CoT for DM Text Message");
                    CotEvent cotEvent = new CotEvent();
                    CoordinatedTime time = new CoordinatedTime();
                    cotEvent.setTime(time);
                    cotEvent.setStart(time);
                    cotEvent.setStale(time.addMinutes(10));

                    cotEvent.setUID("GeoChat." + payload.getFrom() + "." + myNodeID + "." + UUID.randomUUID());
                    CotPoint gp = new CotPoint(0, 0, 0, 0, 0);
                    cotEvent.setPoint(gp);
                    cotEvent.setHow("m-g");
                    cotEvent.setType("b-t-f");

                    CotDetail cotDetail = new CotDetail("detail");
                    cotEvent.setDetail(cotDetail);

                    CotDetail chatDetail = new CotDetail("__chat");
                    chatDetail.setAttribute("parent", "RootContactGroup");
                    chatDetail.setAttribute("groupOwner", "false");
                    chatDetail.setAttribute("messageId", UUID.randomUUID().toString());
                    chatDetail.setAttribute("chatroom", myNodeID);
                    chatDetail.setAttribute("id", myNodeID);
                    chatDetail.setAttribute("senderCallsign", payload.getFrom());
                    cotDetail.addChild(chatDetail);

                    CotDetail chatgrp = new CotDetail("chatgrp");
                    chatgrp.setAttribute("uid0", payload.getFrom());
                    chatgrp.setAttribute("uid1", myNodeID);
                    chatgrp.setAttribute("id", myNodeID);
                    chatDetail.addChild(chatgrp);

                    CotDetail linkDetail = new CotDetail("link");
                    linkDetail.setAttribute("uid", payload.getFrom());
                    linkDetail.setAttribute("type", "a-f-G-U-C");
                    linkDetail.setAttribute("relation", "p-p");
                    cotDetail.addChild(linkDetail);

                    CotDetail serverDestinationDetail = new CotDetail("__serverdestination");
                    serverDestinationDetail.setAttribute("destination", "0.0.0.0:4242:tcp");
                    cotDetail.addChild(serverDestinationDetail);

                    CotDetail remarksDetail = new CotDetail("remarks");
                    remarksDetail.setAttribute("source", "BAO.F.ATAK." + payload.getFrom());
                    remarksDetail.setAttribute("to", myNodeID);
                    remarksDetail.setAttribute("time", time.toString());
                    remarksDetail.setInnerText(new String(payload.getBytes()));
                    cotDetail.addChild(remarksDetail);

                    if (cotEvent.isValid()) {
                        CotMapComponent.getInternalDispatcher().dispatch(cotEvent);
                    } else
                        Log.e(TAG, "cotEvent was not valid");
                }
                break;
            case MeshtasticMapComponent.ACTION_NODE_CHANGE:
                NodeInfo ni = null;
                try {
                    ni = intent.getParcelableExtra("com.geeksville.mesh.NodeInfo");
                } catch (Exception e) {
                    e.printStackTrace();
                    return;
                }

                if (ni == null) {
                    Log.d(TAG, "NodeInfo was null");
                    return;
                }
                if (ni.getUser() == null) {
                    Log.d(TAG, "getUser was null");
                    return;
                }
                if (ni.getPosition() == null) {
                    Log.d(TAG, "getPosition was null");
                    return;
                }
                if (prefs.getBoolean("plugin_meshtastic_nogps", false)) {
                    if (ni.getPosition().getLatitude() == 0 && ni.getPosition().getLongitude() == 0) {
                        Log.d(TAG, "Ignoring NodeInfo with 0,0 GPS");
                        return;
                    }
                    Log.d(TAG, "NodeInfo GPS: " + ni.getPosition().getLatitude() + ", " + ni.getPosition().getLongitude() + ", Ignoring due to preferences");
                }

                Log.d(TAG, ni.toString());

                String myId = MeshtasticMapComponent.getMyNodeID();
                if (myId == null) {
                    Log.d(TAG, "myId was null");
                    return;
                }

                if (ni.getUser().getId().equals(myId) && prefs.getBoolean("plugin_meshtastic_self", false)) {
                    Log.d(TAG, "Ignoring self");
                    return;
                }

                if (prefs.getBoolean("plugin_meshtastic_tracker", false)) {
                    String nodeName = ni.getUser().getLongName();
                    Log.i(TAG, "Node name: " + nodeName);
                    CotDetail groupDetail = new CotDetail("__group");
                    String[] teamColor = {"Unknown", " -0"};
                    try {
                        teamColor = nodeName.split("((?= -[0-9]*$))");
                        Log.d(TAG, String.valueOf(teamColor.length));
                        for (int i=0; i<teamColor.length; i++) {
                            Log.d(TAG, "teamColor[" + i + "]: " + teamColor[i]);
                        }
                        if (teamColor.length < 2) {
                            teamColor = new String[]{nodeName, " -10"};
                        }
                        groupDetail.setAttribute("role", "Team Member");
                        switch (teamColor[1]) {
                            case " -0":
                            case " -1":
                                groupDetail.setAttribute("name", "White");
                                break;
                            case " -2":
                                groupDetail.setAttribute("name", "Yellow");
                                break;
                            case " -3":
                                groupDetail.setAttribute("name", "Orange");
                                break;
                            case " -4":
                                groupDetail.setAttribute("name", "Magenta");
                                break;
                            case " -5":
                                groupDetail.setAttribute("name", "Red");
                                break;
                            case " -6":
                                groupDetail.setAttribute("name", "Maroon");
                                break;
                            case " -7":
                                groupDetail.setAttribute("name", "Purple");
                                break;
                            case " -8":
                                groupDetail.setAttribute("name", "Dark Blue");
                                break;
                            case " -9":
                                groupDetail.setAttribute("name", "Blue");
                                break;
                            case " -10":
                                groupDetail.setAttribute("name", "Cyan");
                                break;
                            case " -11":
                                groupDetail.setAttribute("name", "Teal");
                                break;
                            case " -12":
                                groupDetail.setAttribute("name", "Green");
                                break;
                            case " -13":
                                groupDetail.setAttribute("name", "Dark Green");
                                break;
                            case " -14":
                                groupDetail.setAttribute("name", "Brown");
                                break;
                            default:
                                groupDetail.setAttribute("name", "Black");
                                break;
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                        return;
                    }
                    Log.d(TAG, "Creating CoT for Sensor NodeInfo");
                    CotEvent cotEvent = new CotEvent();
                    CoordinatedTime time = new CoordinatedTime();
                    cotEvent.setTime(time);
                    cotEvent.setStart(time);
                    cotEvent.setStale(time.addMinutes(10));

                    cotEvent.setUID(ni.getUser().getId());
                    CotPoint gp = new CotPoint(ni.getPosition().getLatitude(), ni.getPosition().getLongitude(), ni.getPosition().getAltitude(), CotPoint.UNKNOWN, CotPoint.UNKNOWN);
                    cotEvent.setPoint(gp);
                    cotEvent.setHow("m-g");
                    cotEvent.setType("a-f-G-E-S");

                    CotDetail cotDetail = new CotDetail("detail");
                    cotEvent.setDetail(cotDetail);
                    cotDetail.addChild(groupDetail);

                    if (ni.getDeviceMetrics() != null) {
                        CotDetail batteryDetail = new CotDetail("status");
                        batteryDetail.setAttribute("battery", String.valueOf(ni.getDeviceMetrics().getBatteryLevel()));
                        cotDetail.addChild(batteryDetail);
                    }

                    CotDetail takvDetail = new CotDetail("takv");
                    takvDetail.setAttribute("platform", "Meshtastic Plugin");
                    takvDetail.setAttribute("version", "\n----NodeInfo----\n" + ni.toString());
                    takvDetail.setAttribute("device", ni.getUser().getHwModelString());
                    takvDetail.setAttribute("os", "1");
                    cotDetail.addChild(takvDetail);

                    CotDetail uidDetail = new CotDetail("uid");
                    uidDetail.setAttribute("Droid", teamColor[0]);
                    cotDetail.addChild(uidDetail);

                    CotDetail contactDetail = new CotDetail("contact");
                    contactDetail.setAttribute("callsign", teamColor[0]);
                    contactDetail.setAttribute("endpoint", "0.0.0.0:4242:tcp");
                    cotDetail.addChild(contactDetail);

                    CotDetail meshDetail = new CotDetail("__meshtastic");
                    cotDetail.addChild(meshDetail);

                    if (cotEvent.isValid()) {
                        CotMapComponent.getInternalDispatcher().dispatch(cotEvent);
                        if (prefs.getBoolean("plugin_meshtastic_server", false)) {
                            CotMapComponent.getExternalDispatcher().dispatch(cotEvent);
                        }
                    } else
                        Log.e(TAG, "cotEvent was not valid");
                }
                break;
        }
    }

    HashMap<Integer, byte[]> chunkMap = new HashMap<>();
    boolean chunking = false;
    int chunkSize = 0;
    int chunkCount = 0;
    protected void receive(Intent intent) throws InvalidProtocolBufferException {
        DataPacket payload = intent.getParcelableExtra(MeshtasticMapComponent.EXTRA_PAYLOAD);
        if (payload == null) return;
        int dataType = payload.getDataType();
        Log.v(TAG, "handleReceive(), dataType: " + dataType);

        SharedPreferences.Editor editor = prefs.edit();

        if (dataType == Portnums.PortNum.ATAK_FORWARDER_VALUE) {
            String message = new String(payload.getBytes());
            // user must opt-in for SWITCH message
            if (message.startsWith("SWT") && prefs.getBoolean("plugin_meshtastic_switch", false)) {
                Log.d(TAG, "Received Switch message");

                // flag to indicate we're in file transfer mode
                editor.putBoolean("plugin_meshtastic_file_transfer", true);
                editor.apply();

                sender = payload.getFrom();

                byte[] config;
                config = MeshtasticMapComponent.getConfig();

                // capture old config
                LocalOnlyProtos.LocalConfig c = null;
                try {
                    c = LocalOnlyProtos.LocalConfig.parseFrom(config);
                } catch (InvalidProtocolBufferException e) {
                    Log.d(TAG, "Failed to process Switch packet");
                    e.printStackTrace();
                    return;
                }

                Log.d(TAG, "Config: " + c.toString());
                ConfigProtos.Config.DeviceConfig dc = c.getDevice();
                ConfigProtos.Config.LoRaConfig lc = c.getLora();
                oldModemPreset = lc.getModemPreset().getNumber();

                // set short/fast for file transfer
                ConfigProtos.Config.Builder configBuilder = ConfigProtos.Config.newBuilder();
                AtomicReference<ConfigProtos.Config.LoRaConfig.Builder> loRaConfigBuilder = new AtomicReference<>(lc.toBuilder());
                AtomicReference<ConfigProtos.Config.LoRaConfig.ModemPreset> modemPreset = new AtomicReference<>(ConfigProtos.Config.LoRaConfig.ModemPreset.forNumber(ConfigProtos.Config.LoRaConfig.ModemPreset.SHORT_FAST_VALUE));
                loRaConfigBuilder.get().setModemPreset(modemPreset.get());
                configBuilder.setLora(loRaConfigBuilder.get());
                boolean needReboot;

                // if not already in short/fast mode, switch to it
                if (oldModemPreset != ConfigProtos.Config.LoRaConfig.ModemPreset.SHORT_FAST_VALUE) {
                    ((Activity)MapView.getMapView().getContext()).runOnUiThread(() -> {
                        Toast.makeText(getMapView().getContext(), "Rebooting to Short/Fast for file transfer", Toast.LENGTH_LONG).show();
                    });
                    needReboot = true;
                } else {
                    needReboot = false;
                }

                SharedPreferences.Editor finalEditor = editor;
                new Thread(() -> {
                    try {
                        // hopefully enough time to ACK the SWT command
                        Thread.sleep(2000);

                        // we gotta reboot to short/fast
                        if (needReboot) {
                            try {
                                // flag to indicate we are rebooting into short/fast
                                finalEditor.putBoolean("plugin_meshtastic_shortfast", true);
                                finalEditor.apply();
                                MeshtasticMapComponent.setConfig(configBuilder.build().toByteArray());
                                // wait for ourselves to switch to short/fast
                                while (prefs.getBoolean("plugin_meshtastic_shortfast", false))
                                    Thread.sleep(1000);
                            } catch (InterruptedException e) {
                                e.printStackTrace();
                            }
                        }

                        // wait for file transfer to finish
                        while(prefs.getBoolean("plugin_meshtastic_file_transfer", false))
                            Thread.sleep(10000);

                        if (needReboot) {
                            // restore config
                            loRaConfigBuilder.set(lc.toBuilder());
                            modemPreset.set(ConfigProtos.Config.LoRaConfig.ModemPreset.forNumber(oldModemPreset));
                            loRaConfigBuilder.get().setModemPreset(modemPreset.get());
                            configBuilder.setLora(loRaConfigBuilder.get());
                            MeshtasticMapComponent.setConfig(configBuilder.build().toByteArray());
                        }

                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }).start();

            } else if (message.startsWith("MFT")) {
                // sender side, recv file transfer over
                Log.d(TAG, "Received File message completed");
                editor = prefs.edit();
                editor.putBoolean("plugin_meshtastic_file_transfer", false);
                editor.apply();
            } else if (message.startsWith("CHK")) {
                Log.d(TAG, "Received Chunked message");
                chunking = true;
                if (chunkSize == 0) {
                    chunkSize = Integer.parseInt(message.split("_")[1]);
                    Log.d(TAG, "Chunk size: " + chunkSize);
                }
                int chunk_hdr_size = String.format(Locale.US, "CHK_%d_", chunkSize).getBytes().length;
                byte[] chunk = new byte[payload.getBytes().length - chunk_hdr_size];
                try {
                    System.arraycopy(payload.getBytes(), chunk_hdr_size, chunk, 0, payload.getBytes().length - chunk_hdr_size);
                } catch (Exception e) {
                    e.printStackTrace();
                    Log.d(TAG, "Failed to copy first chunk");
                }

                // check if this chunk has already been received
                if (chunkMap.containsValue(chunk)) {
                    Log.d(TAG, "Chunk already received");
                    return;
                } else {
                    chunkMap.put(Integer.valueOf(chunkCount++), chunk);
                }

                if(prefs.getBoolean("plugin_meshtastic_file_transfer", false)) {
                    // caclulate progress
                    //zi = (xi – min(x)) / (max(x) – min(x)) * 100
                    mBuilder.setProgress(100, (int) Math.floor((chunkMap.size() - 1) / (chunkSize - 1) * 100), false);
                    mNotifyManager.notify(id, mBuilder.build());
                }
            } else if (message.startsWith("END") && chunking) {
                Log.d(TAG, "Chunking");
                byte[] combined = new byte[chunkSize];

                int i = 0;
                for (byte[] b : chunkMap.values()) {
                    try {
                        System.arraycopy(b, 0, combined, i, b.length);
                    } catch (Exception e) {
                        e.printStackTrace();
                        Log.d(TAG, "Failed to copy in chunking");
                    }
                    i += b.length;
                    Log.d(TAG, "" + i);
                }

                // done chunking clear accounting
                chunkSize = 0;
                chunking = false;
                chunkMap.clear();
                chunkCount = 0;

                // this was a file transfer not chunks
                if (prefs.getBoolean("plugin_meshtastic_file_transfer", false)) {
                    Log.d(TAG, "File Received");

                    mBuilder.setContentText("Transfer complete")
                            // Removes the progress bar
                            .setProgress(0,0,false);
                    mNotifyManager.notify(id, mBuilder.build());

                    try {
                        String path = String.format(Locale.US, "%s/%s/%s.zip", Environment.getExternalStorageDirectory().getAbsolutePath(), "atak/tools/datapackage", UUID.randomUUID().toString());
                        Log.d(TAG, "Writing to: " + path);
                        Files.write(new File(path).toPath(), combined);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }

                    // inform sender we're done recv
                    DataPacket dp = new DataPacket(sender, new byte[]{'M', 'F', 'T'}, Portnums.PortNum.ATAK_FORWARDER_VALUE, DataPacket.ID_LOCAL, System.currentTimeMillis(), 0, MessageStatus.UNKNOWN, getHopLimit(), getChannelIndex());
                    MeshtasticMapComponent.sendToMesh(dp);
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                        Log.d(TAG, "MFT interrupted");
                    }

                    //receive side, file transfer over
                    editor = prefs.edit();
                    editor.putBoolean("plugin_meshtastic_file_transfer", false);
                    editor.apply();
                    return;
                }
                try {
                    EXIFactory exiFactory = DefaultEXIFactory.newInstance();
                    StringWriter writer = new StringWriter();
                    Result result = new StreamResult(writer);
                    InputSource is = new InputSource(new ByteArrayInputStream(combined));
                    SAXSource exiSource = new EXISource(exiFactory);
                    exiSource.setInputSource(is);
                    TransformerFactory tf = TransformerFactory.newInstance();
                    Transformer transformer = tf.newTransformer();
                    transformer.transform(exiSource, result);
                    CotEvent cotEvent = CotEvent.parse(writer.toString());

                    if (cotEvent != null && cotEvent.isValid()) {
                        Log.d(TAG, "Chunked CoT Received");
                        CotMapComponent.getInternalDispatcher().dispatch(cotEvent);
                        if (prefs.getBoolean("plugin_meshtastic_server", false)) {
                            CotMapComponent.getExternalDispatcher().dispatch(cotEvent);
                        }
                    } else {
                        Log.d(TAG, "Failed to chunk: " + new String(combined));
                    }
                } catch (Throwable e) {
                    e.printStackTrace();
                }
            } else {
                try {
                    EXIFactory exiFactory = DefaultEXIFactory.newInstance();
                    StringWriter writer = new StringWriter();
                    Result result = new StreamResult(writer);
                    InputSource is = new InputSource(new ByteArrayInputStream(payload.getBytes()));
                    SAXSource exiSource = new EXISource(exiFactory);
                    exiSource.setInputSource(is);
                    TransformerFactory tf = TransformerFactory.newInstance();
                    Transformer transformer = tf.newTransformer();
                    transformer.transform(exiSource, result);
                    CotEvent cotEvent = CotEvent.parse(writer.toString());
                    if ( cotEvent != null && cotEvent.isValid()) {
                        CotMapComponent.getInternalDispatcher().dispatch(cotEvent);
                        if (prefs.getBoolean("plugin_meshtastic_server", false)) {
                            CotMapComponent.getExternalDispatcher().dispatch(cotEvent);
                        }
                    }
                } catch (Throwable e) {
                    e.printStackTrace();
                }
            }
        } else if (dataType == 72) {
            Log.d(TAG, "Got TAK_PACKET");
            Log.d(TAG, "Payload: " + payload);
            String t = "";
            for (int i = 0; i< payload.getBytes().length; i++) {
                // convert bytes to ascii
                t += (char) payload.getBytes()[i];
            }
            Log.d(TAG, "Payload: " + t);
            try {
                ATAKProtos.TAKPacket tp = ATAKProtos.TAKPacket.parseFrom(payload.getBytes());
                if (tp.getIsCompressed()) {
                    Log.d(TAG, "TAK_PACKET is compressed");
                    return;
                }
                Log.d(TAG, "TAK_PACKET: " + tp.toString());
                if (tp.hasPli()) {
                    Log.d(TAG, "TAK_PACKET PLI");
                    ATAKProtos.Contact contact = tp.getContact();
                    ATAKProtos.Group group = tp.getGroup();
                    ATAKProtos.Status status = tp.getStatus();
                    ATAKProtos.PLI pli = tp.getPli();

                    double lat = pli.getLatitudeI() * 1e-7;
                    double lng = pli.getLongitudeI() * 1e-7;
                    double alt = pli.getAltitude();
                    int course = pli.getCourse();
                    int speed = pli.getSpeed();

                    Log.d(TAG, String.format("GPS: %f %f %f", alt, lat, lng));

                    String callsign = contact.getCallsign();
                    String deviceCallsign = contact.getDeviceCallsign();

                    CotDetail cotDetail = new CotDetail("detail");

                    CotDetail uidDetail = new CotDetail("uid");
                    uidDetail.setAttribute("Droid", callsign);

                    CotDetail contactDetail = new CotDetail("contact");
                    contactDetail.setAttribute("callsign", callsign);
                    contactDetail.setAttribute("endpoint", "0.0.0.0:4242:tcp");
                    cotDetail.addChild(contactDetail);

                    CotDetail groupDetail = new CotDetail("__group");

                    String role = ATAKProtos.MemberRole.forNumber(group.getRoleValue()).name();
                    switch (role) {
                        case "TeamMember":
                            role = "Team Member";
                            break;
                        case "TeamLead":
                            role = "Team Lead";
                            break;
                        case "ForwardObserver":
                            role = "Forward Observer";
                            break;
                    }
                    groupDetail.setAttribute("role", role);

                    String team = ATAKProtos.Team.forNumber(group.getTeamValue()).name();
                    if (team.equals("DarkBlue"))
                        team = "Dark Blue";
                    else if (team.equals("DarkGreen"))
                        team = "Dark Green";
                    groupDetail.setAttribute("name", team);
                    cotDetail.addChild(groupDetail);

                    CotDetail statusDetail = new CotDetail("status");
                    statusDetail.setAttribute("battery", String.valueOf(status.getBattery()));
                    cotDetail.addChild(statusDetail);

                    CotDetail trackDetail = new CotDetail("track");
                    trackDetail.setAttribute("speed", String.valueOf(speed));
                    trackDetail.setAttribute("course", String.valueOf(course));
                    cotDetail.addChild(trackDetail);

                    CotEvent cotEvent = new CotEvent();
                    cotEvent.setDetail(cotDetail);
                    cotEvent.setUID(deviceCallsign);

                    CoordinatedTime time = new CoordinatedTime();
                    cotEvent.setTime(time);
                    cotEvent.setStart(time);
                    cotEvent.setStale(time.addMinutes(10));

                    cotEvent.setType("a-f-G-U-C");

                    cotEvent.setHow("m-g");

                    CotDetail meshDetail = new CotDetail("__meshtastic");
                    cotDetail.addChild(meshDetail);

                    CotPoint cotPoint = new CotPoint(lat, lng, CotPoint.UNKNOWN,
                            CotPoint.UNKNOWN, CotPoint.UNKNOWN);
                    cotEvent.setPoint(cotPoint);

                    if (cotEvent.isValid()) {
                        CotMapComponent.getInternalDispatcher().dispatch(cotEvent);
                        if (prefs.getBoolean("plugin_meshtastic_server", false)) {
                            CotMapComponent.getExternalDispatcher().dispatch(cotEvent);
                        }
                    } else
                        Log.e(TAG, "cotEvent was not valid");


              /*
            <?xml version='1.0' encoding='UTF-8' standalone='yes'?>
            <event version='2.0' uid='GeoChat.ANDROID-e612f0e922b56a63.All Chat Rooms.d22bcfac-2c28-4e0c-8133-172928ba59b7' type='b-t-f' time='2024-02-07T19:02:09.192Z' start='2024-02-07T19:02:09.192Z' stale='2024-02-08T19:02:09.192Z' how='h-g-i-g-o'>
            <point lat='40.2392345' lon='-19.7690137' hae='9999999.0' ce='9999999.0' le='9999999.0' />
                <detail>
                    <__chat parent='RootContactGroup' groupOwner='false' messageId='d22bcfac-2c28-4e0c-8133-172928ba59b7' chatroom='All Chat Rooms' id='All Chat Rooms' senderCallsign='FALKE lol'>
                        <chatgrp uid0='ANDROID-e612f0e922b56a63' uid1='All Chat Rooms' id='All Chat Rooms'/>
                    </__chat>
                    <link uid='ANDROID-e612f0e922b56a63' type='a-f-G-U-C' relation='p-p'/>
                    <__serverdestination destinations='0.0.0.0:4242:tcp:ANDROID-e612f0e922b56a63'/>
                    <remarks source='BAO.F.ATAK.ANDROID-e612f0e922b56a63' to='All Chat Rooms' time='2024-02-07T19:02:09.192Z'>lol</remarks>
                </detail>
            </event>
             */
                } else if (tp.hasChat() && tp.getChat().getTo().equals("All Chat Rooms")) {
                    Log.d(TAG, "TAK_PACKET GEOCHAT - All Chat Rooms");

                    ATAKProtos.Contact contact = tp.getContact();
                    ATAKProtos.GeoChat geoChat = tp.getChat();

                    String callsign = contact.getCallsign();
                    String deviceCallsign = contact.getDeviceCallsign();
                    String msgId = callsign + "-" + deviceCallsign + "-" + geoChat.getMessage().hashCode() + "-" + System.currentTimeMillis();

                    //Bundle chatMessage = ChatDatabase.getInstance(_mapView.getContext()).getChatMessage(msgId);
                    //if (chatMessage != null) {
                    //    Log.d(TAG, "Duplicate message");
                    //    return;
                    //}

                    if (prefs.getBoolean("plugin_meshtastic_voice", false)) {
                        StringBuilder message = new StringBuilder();
                        message.append("GeoChat from ");
                        message.append(callsign);
                        message.append(" ");
                        message.append(geoChat.getMessage());
                        MeshtasticDropDownReceiver.t1.speak(message.toString(), TextToSpeech.QUEUE_FLUSH, null);
                    }

                    CotDetail cotDetail = new CotDetail("detail");

                    CoordinatedTime time = new CoordinatedTime();

                    CotDetail chatDetail = new CotDetail("__chat");
                    chatDetail.setAttribute("parent", "RootContactGroup");
                    chatDetail.setAttribute("groupOwner", "false");
                    chatDetail.setAttribute("messageId", msgId);
                    chatDetail.setAttribute("chatroom", "All Chat Rooms");
                    chatDetail.setAttribute("id", "All Chat Rooms");
                    chatDetail.setAttribute("senderCallsign", callsign);
                    cotDetail.addChild(chatDetail);

                    CotDetail chatgrp = new CotDetail("chatgrp");
                    chatgrp.setAttribute("uid0", deviceCallsign);
                    chatgrp.setAttribute("uid1", "All Chat Rooms");
                    chatgrp.setAttribute("id", "All Chat Rooms");
                    chatDetail.addChild(chatgrp);

                    CotDetail linkDetail = new CotDetail("link");
                    linkDetail.setAttribute("uid", deviceCallsign);
                    linkDetail.setAttribute("type", "a-f-G-U-C");
                    linkDetail.setAttribute("relation", "p-p");
                    cotDetail.addChild(linkDetail);

                    CotDetail serverDestinationDetail = new CotDetail("__serverdestination");
                    serverDestinationDetail.setAttribute("destination", "0.0.0.0:4242:tcp");
                    cotDetail.addChild(serverDestinationDetail);

                    CotDetail meshDetail = new CotDetail("__meshtastic");
                    cotDetail.addChild(meshDetail);

                    CotDetail remarksDetail = new CotDetail("remarks");
                    remarksDetail.setAttribute("source", String.format("BAO.F.ATAK.%s", deviceCallsign));
                    remarksDetail.setAttribute("to", "All Chat Rooms");
                    remarksDetail.setAttribute("time", time.toString());
                    remarksDetail.setInnerText(geoChat.getMessage());
                    cotDetail.addChild(remarksDetail);

                    CotEvent cotEvent = new CotEvent();
                    cotEvent.setDetail(cotDetail);
                    cotEvent.setUID("GeoChat." + deviceCallsign + ".All Chat Rooms." + msgId);
                    cotEvent.setTime(time);
                    cotEvent.setStart(time);
                    cotEvent.setStale(time.addMinutes(10));
                    cotEvent.setType("b-t-f");
                    cotEvent.setHow("h-g-i-g-o");

                    CotPoint cotPoint = new CotPoint(0, 0, CotPoint.UNKNOWN,
                            CotPoint.UNKNOWN, CotPoint.UNKNOWN);
                    cotEvent.setPoint(cotPoint);

                    if (cotEvent.isValid()) {
                        CotMapComponent.getInternalDispatcher().dispatch(cotEvent);
                        if (prefs.getBoolean("plugin_meshtastic_server", false)) {
                            CotMapComponent.getExternalDispatcher().dispatch(cotEvent);
                        }
                    } else
                        Log.e(TAG, "cotEvent was not valid");

                } else if (tp.hasChat() && tp.getChat().getTo().equals(getMapView().getSelfMarker().getUID())) {
                    /*
                    <?xml version='1.0' encoding='UTF-8' standalone='yes'?>
                    <event version='2.0' uid='GeoChat.ANDROID-e612f0e922b56a63.ANDROID-b5c2b8340a0a2cd5.23c1f487-7111-4995-89f5-7709a9c99518' type='b-t-f' time='2024-02-07T19:04:06.683Z' start='2024-02-07T19:04:06.683Z' stale='2024-02-08T19:04:06.683Z' how='h-g-i-g-o'>
                    <point lat='40.2392345' lon='-19.7690137' hae='9999999.0' ce='9999999.0' le='9999999.0' />
                    <detail>
                        <__chat parent='RootContactGroup' groupOwner='false' messageId='23c1f487-7111-4995-89f5-7709a9c99518' chatroom='HUSKER lol' id='ANDROID-b5c2b8340a0a2cd5' senderCallsign='FALKE lol'>
                            <chatgrp uid0='ANDROID-e612f0e922b56a63' uid1='ANDROID-b5c2b8340a0a2cd5' id='ANDROID-b5c2b8340a0a2cd5'/>
                        </__chat>
                        <link uid='ANDROID-e612f0e922b56a63' type='a-f-G-U-C' relation='p-p'/>
                        <__serverdestination destinations='0.0.0.0:4242:tcp:ANDROID-e612f0e922b56a63'/>
                        <remarks source='BAO.F.ATAK.ANDROID-e612f0e922b56a63' to='ANDROID-b5c2b8340a0a2cd5' time='2024-02-07T19:04:06.683Z'>at breach</remarks>
                    </detail>
                    </event>
                     */

                    Log.d(TAG, "TAK_PACKET GEOCHAT - DM");

                    ATAKProtos.Contact contact = tp.getContact();
                    ATAKProtos.GeoChat geoChat = tp.getChat();

                    String to = geoChat.getTo();
                    String callsign = contact.getCallsign();
                    String deviceCallsign = contact.getDeviceCallsign();
                    String msgId = callsign + "-" + deviceCallsign + "-" + geoChat.getMessage().hashCode() + "-" + System.currentTimeMillis();

                    //Bundle chatMessage = ChatDatabase.getInstance(_mapView.getContext()).getChatMessage(msgId);
                    //if (chatMessage != null) {
                    //    Log.d(TAG, "Duplicate message");
                    //    return;
                    //}

                    if (prefs.getBoolean("plugin_meshtastic_voice", false)) {
                        StringBuilder message = new StringBuilder();
                        message.append("GeoChat from ");
                        message.append(callsign);
                        message.append(" ");
                        message.append(geoChat.getMessage());
                        MeshtasticDropDownReceiver.t1.speak(message.toString(), TextToSpeech.QUEUE_FLUSH, null);
                    }

                    CoordinatedTime time = new CoordinatedTime();

                    CotDetail cotDetail = new CotDetail("detail");
                    CotDetail chatDetail = new CotDetail("__chat");
                    chatDetail.setAttribute("parent", "RootContactGroup");
                    chatDetail.setAttribute("groupOwner", "false");
                    chatDetail.setAttribute("messageId", msgId);
                    chatDetail.setAttribute("chatroom", getMapView().getDeviceCallsign());
                    chatDetail.setAttribute("id", getMapView().getSelfMarker().getUID());
                    chatDetail.setAttribute("senderCallsign", callsign);
                    cotDetail.addChild(chatDetail);

                    CotDetail chatgrp = new CotDetail("chatgrp");
                    chatgrp.setAttribute("uid0", deviceCallsign);
                    chatgrp.setAttribute("uid1", getMapView().getSelfMarker().getUID());
                    chatgrp.setAttribute("id", getMapView().getSelfMarker().getUID());
                    chatDetail.addChild(chatgrp);

                    CotDetail linkDetail = new CotDetail("link");
                    linkDetail.setAttribute("uid", deviceCallsign);
                    linkDetail.setAttribute("type", "a-f-G-U-C");
                    linkDetail.setAttribute("relation", "p-p");
                    cotDetail.addChild(linkDetail);

                    CotDetail serverDestinationDetail = new CotDetail("__serverdestination");
                    serverDestinationDetail.setAttribute("destination", "0.0.0.0:4242:tcp");
                    cotDetail.addChild(serverDestinationDetail);

                    CotDetail remarksDetail = new CotDetail("remarks");
                    remarksDetail.setAttribute("source", String.format("BAO.F.ATAK.%s", deviceCallsign));
                    remarksDetail.setAttribute("to", to);
                    remarksDetail.setAttribute("time", time.toString());
                    remarksDetail.setInnerText(geoChat.getMessage());
                    cotDetail.addChild(remarksDetail);

                    CotEvent cotEvent = new CotEvent();
                    cotEvent.setDetail(cotDetail);
                    cotEvent.setUID("GeoChat." + deviceCallsign + getMapView().getSelfMarker().getUID() + msgId);
                    cotEvent.setTime(time);
                    cotEvent.setStart(time);
                    cotEvent.setStale(time.addMinutes(10));
                    cotEvent.setType("b-t-f");
                    cotEvent.setHow("h-g-i-g-o");

                    CotDetail meshDetail = new CotDetail("__meshtastic");
                    cotDetail.addChild(meshDetail);

                    CotPoint cotPoint = new CotPoint(0, 0, CotPoint.UNKNOWN,
                            CotPoint.UNKNOWN, CotPoint.UNKNOWN);
                    cotEvent.setPoint(cotPoint);

                    if (cotEvent.isValid()) {
                        CotMapComponent.getInternalDispatcher().dispatch(cotEvent);
                    } else
                        Log.e(TAG, "cotEvent was not valid");
                }

            } catch (InvalidProtocolBufferException e) {
                e.printStackTrace();
            }
        }
    }

    public int getHopLimit() {
        try {
            int hopLimit = prefs.getInt("plugin_meshtastic_hop_limit", 3);
            Log.d(TAG, "Hop Limit: " + hopLimit);
            if (hopLimit > 8) {
                hopLimit = 8;
            }
            return hopLimit;
        } catch (Exception e) {
            e.printStackTrace();
            return 3;
        }
    }

    public static int getChannelIndex() {
        try {
            int channel = Integer.valueOf(prefs.getString("plugin_meshtastic_channel", "0"));
            Log.d(TAG, "Channel: " + channel);
            return channel;
        } catch (Exception e) {
            e.printStackTrace();
            return 0;
        }
    }

    @Override
    public void onCotEvent(CotEvent cotEvent, Bundle bundle) {

        if (prefs.getBoolean("plugin_meshtastic_from_server", false)) {
            if (cotEvent.isValid()) {

                CotDetail cotDetail = cotEvent.getDetail();

                if (cotDetail.getChild("__meshtastic") != null) {
                    Log.d(TAG, "Meshtastic CoT from server");
                    return;
                }

                Log.d(TAG, "onCotEvent");
                Log.d(TAG, cotEvent.toString());

                int hopLimit = getHopLimit();
                int channel = getChannelIndex();

                DataPacket dp = null;
                int eventType = -1;
                double divisor = 1e-7;
                XmlPullParserFactory factory = null;
                XmlPullParser xpp = null;
                String callsign = null;
                String deviceCallsign = null;
                String message = null;

                try {
                    factory = XmlPullParserFactory.newInstance();
                    factory.setNamespaceAware(true);
                    xpp = factory.newPullParser();
                    xpp.setInput(new StringReader(cotDetail.toString()));
                    eventType = xpp.getEventType();
                } catch (XmlPullParserException e) {
                    e.printStackTrace();
                    return;
                }

                // All Chat Rooms
                if (cotEvent.getType().startsWith("b-t-f") && cotEvent.getUID().contains("All Chat Rooms")) {
                    try {
                        while (eventType != XmlPullParser.END_DOCUMENT) {
                            if (eventType == XmlPullParser.START_TAG) {
                                Log.d(TAG, xpp.getName());
                                if (xpp.getName().equalsIgnoreCase("remarks")) {
                                    if (xpp.next() == XmlPullParser.TEXT)
                                        message = xpp.getText();
                                } else if (xpp.getName().equalsIgnoreCase("__chat")) {
                                    int attributeCount = xpp.getAttributeCount();
                                    Log.d(TAG, "__chat has " + +attributeCount);
                                    for (int i = 0; i < attributeCount; i++) {
                                        if (xpp.getAttributeName(i).equalsIgnoreCase("senderCallsign"))
                                            callsign = xpp.getAttributeValue(i);
                                    }
                                } else if (xpp.getName().equalsIgnoreCase("link")) {
                                    int attributeCount = xpp.getAttributeCount();
                                    Log.d(TAG, "link has " + +attributeCount);
                                    for (int i = 0; i < attributeCount; i++) {
                                        if (xpp.getAttributeName(i).equalsIgnoreCase("uid"))
                                            deviceCallsign = xpp.getAttributeValue(i);
                                    }
                                }
                            }
                            eventType = xpp.next();
                        }

                    } catch (XmlPullParserException | IOException e) {
                        e.printStackTrace();
                    }

                    ATAKProtos.Contact.Builder contact = ATAKProtos.Contact.newBuilder();
                    contact.setCallsign(callsign);
                    contact.setDeviceCallsign(deviceCallsign);

                    ATAKProtos.GeoChat.Builder geochat = ATAKProtos.GeoChat.newBuilder();
                    geochat.setMessage(message);
                    geochat.setTo("All Chat Rooms");

                    ATAKProtos.TAKPacket.Builder tak_packet = ATAKProtos.TAKPacket.newBuilder();
                    tak_packet.setContact(contact);
                    tak_packet.setChat(geochat);

                    Log.d(TAG, "Total wire size for TAKPacket: " + tak_packet.build().toByteArray().length);
                    Log.d(TAG, "Sending: " + tak_packet.build().toString());

                    dp = new DataPacket(DataPacket.ID_BROADCAST, tak_packet.build().toByteArray(), Portnums.PortNum.ATAK_PLUGIN_VALUE, DataPacket.ID_LOCAL, System.currentTimeMillis(), 0, MessageStatus.UNKNOWN, hopLimit, channel);
                    try {
                        if (MeshtasticMapComponent.getMeshService() != null)
                            MeshtasticMapComponent.getMeshService().send(dp);
                    } catch (RemoteException e) {
                        throw new RuntimeException(e);
                    }
                } else if (cotDetail.getAttribute("contact") != null) {
                    for (Contact c : Contacts.getInstance().getAllContacts()) {
                        if (cotEvent.getUID().equals(c.getUid())) {

                            int battery = 0, course = 0, speed = 0;
                            String role = null, name = null;
                            double lat = 0, lng = 0, alt = 0;

                            lat = cotEvent.getGeoPoint().getLatitude();
                            lng = cotEvent.getGeoPoint().getLongitude();
                            alt = cotEvent.getGeoPoint().getAltitude();

                            try {
                                while (eventType != XmlPullParser.END_DOCUMENT) {
                                    if (eventType == XmlPullParser.START_TAG) {
                                        if (xpp.getName().equalsIgnoreCase("contact")) {
                                            int attributeCount = xpp.getAttributeCount();
                                            Log.d(TAG, "Contact has " + attributeCount);
                                            for (int i = 0; i < attributeCount; i++) {
                                                if (xpp.getAttributeName(i).equalsIgnoreCase("callsign"))
                                                    callsign = xpp.getAttributeValue(i);
                                            }
                                        } else if (xpp.getName().equalsIgnoreCase("__group")) {
                                            int attributeCount = xpp.getAttributeCount();
                                            Log.d(TAG, "__group has " + attributeCount);
                                            for (int i = 0; i < attributeCount; i++) {
                                                if (xpp.getAttributeName(i).equalsIgnoreCase("role"))
                                                    role = xpp.getAttributeValue(i);
                                                else if (xpp.getAttributeName(i).equalsIgnoreCase("name"))
                                                    name = xpp.getAttributeValue(i);
                                            }
                                        } else if (xpp.getName().equalsIgnoreCase("status")) {
                                            int attributeCount = xpp.getAttributeCount();
                                            Log.d(TAG, "status has " + attributeCount);
                                            for (int i = 0; i < attributeCount; i++) {
                                                if (xpp.getAttributeName(i).equalsIgnoreCase("battery"))
                                                    battery = Integer.parseInt(xpp.getAttributeValue(i));
                                            }
                                        } else if (xpp.getName().equalsIgnoreCase("track")) {
                                            int attributeCount = xpp.getAttributeCount();
                                            Log.d(TAG, "track has " + attributeCount);
                                            for (int i = 0; i < attributeCount; i++) {
                                                if (xpp.getAttributeName(i).equalsIgnoreCase("course"))
                                                    course = Double.valueOf(xpp.getAttributeValue(i)).intValue();
                                                else if (xpp.getAttributeName(i).equalsIgnoreCase("speed"))
                                                    speed = Double.valueOf(xpp.getAttributeValue(i)).intValue();
                                            }
                                        }
                                    }
                                    eventType = xpp.next();
                                }
                            } catch (XmlPullParserException | IOException e) {
                                e.printStackTrace();
                                return;
                            }

                            ATAKProtos.Contact.Builder contact = ATAKProtos.Contact.newBuilder();
                            contact.setCallsign(callsign);
                            contact.setDeviceCallsign(cotEvent.getUID());

                            ATAKProtos.Group.Builder group = ATAKProtos.Group.newBuilder();
                            group.setRole(ATAKProtos.MemberRole.valueOf(role.replace(" ", "")));
                            group.setTeam(ATAKProtos.Team.valueOf(name.replace(" ", "")));

                            ATAKProtos.Status.Builder status = ATAKProtos.Status.newBuilder();
                            status.setBattery(battery);

                            ATAKProtos.PLI.Builder pli = ATAKProtos.PLI.newBuilder();
                            pli.setAltitude(Double.valueOf(alt).intValue());
                            pli.setLatitudeI((int) (lat / divisor));
                            pli.setLongitudeI((int) (lng / divisor));
                            pli.setCourse(course);
                            pli.setSpeed(speed);

                            ATAKProtos.TAKPacket.Builder tak_packet = ATAKProtos.TAKPacket.newBuilder();
                            tak_packet.setContact(contact);
                            tak_packet.setStatus(status);
                            tak_packet.setGroup(group);
                            tak_packet.setPli(pli);

                            Log.d(TAG, "Total wire size for TAKPacket: " + tak_packet.build().toByteArray().length);
                            Log.d(TAG, "Sending: " + tak_packet.build().toString());

                            dp = new DataPacket(DataPacket.ID_BROADCAST, tak_packet.build().toByteArray(), Portnums.PortNum.ATAK_PLUGIN_VALUE, DataPacket.ID_LOCAL, System.currentTimeMillis(), 0, MessageStatus.UNKNOWN, hopLimit, channel);
                            try {
                                if (MeshtasticMapComponent.getMeshService() != null)
                                    MeshtasticMapComponent.getMeshService().send(dp);
                            } catch (RemoteException e) {
                                throw new RuntimeException(e);
                            }
                        }
                    }
                }
            }
        }
    }

}
