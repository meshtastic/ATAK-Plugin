package com.atakmap.android.meshtastic;

import static android.content.Context.NOTIFICATION_SERVICE;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

import android.content.SharedPreferences;
import android.os.Environment;
import android.os.RemoteException;
import android.preference.PreferenceManager;
import android.speech.tts.TextToSpeech;

import androidx.core.app.NotificationCompat;

import com.atakmap.android.cot.CotMapComponent;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.meshtastic.plugin.R;
import com.atakmap.coremap.cot.event.CotDetail;
import com.atakmap.coremap.cot.event.CotEvent;
import com.atakmap.coremap.cot.event.CotPoint;
import com.atakmap.coremap.filesystem.FileSystemUtils;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.time.CoordinatedTime;

import com.geeksville.mesh.ATAKProtos;
import com.geeksville.mesh.AppOnlyProtos;
import com.geeksville.mesh.ChannelProtos;
import com.geeksville.mesh.ConfigProtos;
import com.geeksville.mesh.DataPacket;

import com.geeksville.mesh.LocalOnlyProtos;
import com.geeksville.mesh.MeshProtos;
import com.geeksville.mesh.MeshUser;
import com.geeksville.mesh.MessageStatus;
import com.geeksville.mesh.MyNodeInfo;
import com.geeksville.mesh.NodeInfo;
import com.geeksville.mesh.Portnums;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;


import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

public class MeshtasticReceiver extends BroadcastReceiver {
    private final String TAG = "MeshtasticReceiver";
    private SharedPreferences prefs;
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
                    (NotificationManager) MapView.getMapView().getContext()
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
            PendingIntent appIntent = PendingIntent.getActivity(MapView.getMapView().getContext(), 0, atakFrontIntent, PendingIntent.FLAG_IMMUTABLE);

            mBuilder = new NotificationCompat.Builder(context, "com.atakmap.android.meshtastic");
            mBuilder.setContentTitle("Meshtastic File Transfer")
                    .setContentText("Transfer in progress")
                    .setSmallIcon(R.drawable.ic_launcher)
                    .setAutoCancel(true)
                    .setOngoing(false)
                    .setContentIntent(appIntent);
        }

        prefs = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
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
                if (prefs.getInt("plugin_meshtastic_message_id", 0) == id && status == MessageStatus.DELIVERED) {
                    SharedPreferences.Editor editor = prefs.edit();
                    editor.putBoolean("plugin_meshtastic_switch_ACK", false);
                    editor.apply();
                    Log.d(TAG, "Got ACK from Switch");
                } else if (prefs.getInt("plugin_meshtastic_chunk_id", 0) == id && status == MessageStatus.DELIVERED) {
                    SharedPreferences.Editor editor = prefs.edit();
                    editor.putBoolean("plugin_meshtastic_chunk_ACK", false);
                    editor.putBoolean("plugin_meshtastic_chunk_ERR", false);
                    editor.apply();
                    Log.d(TAG, "Got ACK from Chunk");
                } else if (prefs.getInt("plugin_meshtastic_chunk_id", 0) == id && status == MessageStatus.ERROR) {
                    SharedPreferences.Editor editor = prefs.edit();
                    editor.putBoolean("plugin_meshtastic_chunk_ERR", true);
                    editor.apply();
                    Log.d(TAG, "Got ERROR from File");
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
                        //    CotMapComponent.getExternalDispatcher().dispatch(cotEvent);
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
                NodeInfo ni = intent.getParcelableExtra("com.geeksville.mesh.NodeInfo");
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
                    takvDetail.setAttribute("version", "1.0.21" + "\n----NodeInfo----\n" + ni.toString());
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
    List<byte[]> chunks = new ArrayList<>();
    boolean chunking = false;
    int cotSize = 0;
    protected void receive(Intent intent) throws InvalidProtocolBufferException {
        DataPacket payload = intent.getParcelableExtra(MeshtasticMapComponent.EXTRA_PAYLOAD);
        if (payload == null) return;
        int dataType = payload.getDataType();
        Log.v(TAG, "handleReceive(), dataType: " + dataType);

        if (dataType == Portnums.PortNum.ATAK_FORWARDER_VALUE) {
            String message = new String(payload.getBytes());
            if (message.startsWith("SWT") && prefs.getBoolean("plugin_meshtastic_switch", false)) {
                Log.d(TAG, "Received Switch message");
                SharedPreferences.Editor editor = prefs.edit();
                editor.putBoolean("plugin_meshtastic_file_transfer", true);
                editor.apply();

                sender = payload.getFrom();

                new Thread(() -> {
                    try {

                        Thread.sleep(6000);

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
                        MeshtasticMapComponent.setConfig(configBuilder.build().toByteArray());

                        editor.putBoolean("plugin_meshtastic_shortfast", true);
                        editor.apply();

                        // wait for file transfer to finish
                        while(prefs.getBoolean("plugin_meshtastic_file_transfer", false))
                            Thread.sleep(10000);

                        // restore config
                        loRaConfigBuilder.set(lc.toBuilder());
                        modemPreset.set(ConfigProtos.Config.LoRaConfig.ModemPreset.forNumber(oldModemPreset));
                        loRaConfigBuilder.get().setModemPreset(modemPreset.get());
                        configBuilder.setLora(loRaConfigBuilder.get());
                        MeshtasticMapComponent.setConfig(configBuilder.build().toByteArray());

                        // file transfer is over
                        editor.putBoolean("plugin_meshtastic_file_transfer", false);

                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }).start();

            } else if (message.startsWith("MFT")) {
                // sender side, recv file transfer over
                Log.d(TAG, "Received File message completed");
                SharedPreferences.Editor editor = prefs.edit();
                editor.putBoolean("plugin_meshtastic_file_transfer", false);
                editor.apply();
            } else if (message.startsWith("CHK")) {
                Log.d(TAG, "Received Chunked message");
                chunking = true;
                if (cotSize == 0) {
                    cotSize = Integer.parseInt(message.split("_")[1]);
                    Log.d(TAG, "Chunk size: " + cotSize);
                }
                int chunk_hdr_size = String.format(Locale.US, "CHK_%d_", cotSize).getBytes().length;
                byte[] chunk = new byte[payload.getBytes().length - chunk_hdr_size];
                try {
                    System.arraycopy(payload.getBytes(), chunk_hdr_size, chunk, 0, payload.getBytes().length - chunk_hdr_size);
                } catch (Exception e) {
                    e.printStackTrace();
                    Log.d(TAG, "Failed to copy first chunk");
                }
                chunks.add(chunk);

                if(prefs.getBoolean("plugin_meshtastic_file_transfer", false)) {
                    // caclulate progress
                    //zi = (xi – min(x)) / (max(x) – min(x)) * 100
                    mBuilder.setProgress(100, (int) Math.floor((chunks.size() - 1) / (cotSize - 1) * 100), false);
                    mNotifyManager.notify(id, mBuilder.build());
                }


            } else if (message.startsWith("END") && chunking) {
                Log.d(TAG, "Chunking");
                byte[] combined = new byte[cotSize];

                int i = 0;
                for (byte[] b : chunks) {
                    try {
                        System.arraycopy(b, 0, combined, i, b.length);
                    } catch (Exception e) {
                        e.printStackTrace();
                        Log.d(TAG, "Failed to copy in chunking");
                    }
                    i += b.length;
                    Log.d(TAG, "" + i);
                }

                cotSize = 0;
                chunking = false;
                chunks.clear();

                // this was a file transfer not libcotshrink
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
                    DataPacket dp = new DataPacket(sender, new byte[]{'M', 'F', 'T'}, Portnums.PortNum.ATAK_FORWARDER_VALUE, DataPacket.ID_LOCAL, System.currentTimeMillis(), 0, MessageStatus.UNKNOWN, 3, prefs.getInt("plugin_meshtastic_channel", 0));
                    MeshtasticMapComponent.sendToMesh(dp);
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                        Log.d(TAG, "MFT interrupted");
                    }

                    //receive side, file transfer over
                    SharedPreferences.Editor editor = prefs.edit();
                    editor.putBoolean("plugin_meshtastic_file_transfer", false);
                    editor.apply();
                    return;
                }
                try {
                    CotEvent cotEvent = MeshtasticMapComponent.cotShrinker.toCotEvent(combined);
                    if (cotEvent != null && cotEvent.isValid()) {
                        Log.d(TAG, "Chunked CoT Received");
                        CotMapComponent.getInternalDispatcher().dispatch(cotEvent);
                        if (prefs.getBoolean("plugin_meshtastic_server", false)) {
                            CotMapComponent.getExternalDispatcher().dispatch(cotEvent);
                        }
                    } else {
                        Log.d(TAG, "Failed to libcotshrink: " + new String(combined));
                    }
                } catch (Throwable e) {
                    e.printStackTrace();
                }
            } else {
                try {

                    CotEvent cotEvent = MeshtasticMapComponent.cotShrinker.toCotEvent(payload.getBytes());
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
                    String msgId = String.valueOf(UUID.randomUUID());

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

                } else if (tp.hasChat() && tp.getChat().getTo().equals(MapView.getMapView().getSelfMarker().getUID())) {
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

                    String callsign = contact.getCallsign();
                    String deviceCallsign = contact.getDeviceCallsign();
                    String msgId = String.valueOf(UUID.randomUUID());
                    String to = geoChat.getTo();

                    CotDetail cotDetail = new CotDetail("detail");

                    CoordinatedTime time = new CoordinatedTime();

                    CotDetail chatDetail = new CotDetail("__chat");
                    chatDetail.setAttribute("parent", "RootContactGroup");
                    chatDetail.setAttribute("groupOwner", "false");
                    chatDetail.setAttribute("messageId", msgId);
                    chatDetail.setAttribute("chatroom", MapView.getMapView().getDeviceCallsign());
                    chatDetail.setAttribute("id", MapView.getMapView().getSelfMarker().getUID());
                    chatDetail.setAttribute("senderCallsign", callsign);
                    cotDetail.addChild(chatDetail);

                    CotDetail chatgrp = new CotDetail("chatgrp");
                    chatgrp.setAttribute("uid0", deviceCallsign);
                    chatgrp.setAttribute("uid1", MapView.getMapView().getSelfMarker().getUID());
                    chatgrp.setAttribute("id", MapView.getMapView().getSelfMarker().getUID());
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
                    cotEvent.setUID("GeoChat." + deviceCallsign + MapView.getMapView().getSelfMarker().getUID() + msgId);
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
                    } else
                        Log.e(TAG, "cotEvent was not valid");
                }

            } catch (InvalidProtocolBufferException e) {
                e.printStackTrace();
            }
        }
    }
}
