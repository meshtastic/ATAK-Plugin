package com.atakmap.android.meshtastic;

import static android.content.Context.NOTIFICATION_SERVICE;

import static com.atakmap.android.maps.MapView.*;
import static com.atakmap.android.meshtastic.util.Constants.PREF_PLUGIN_SHORTTURBO;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

import android.content.SharedPreferences;
import android.content.pm.PackageManager;

import com.atakmap.android.meshtastic.util.Constants;
import com.atakmap.android.meshtastic.util.AckManager;
import com.atakmap.android.meshtastic.util.FileTransferManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;

import android.os.Bundle;
import android.os.Environment;
import android.os.RemoteException;
import android.preference.PreferenceManager;
import android.speech.tts.TextToSpeech;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;

import com.siemens.ct.exi.core.EXIFactory;
import com.siemens.ct.exi.core.helpers.DefaultEXIFactory;
import com.siemens.ct.exi.main.api.sax.EXISource;
import com.ustadmobile.codec2.Codec2;

import org.xml.sax.InputSource;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import javax.xml.transform.Result;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.sax.SAXSource;
import javax.xml.transform.stream.StreamResult;
import com.atakmap.coremap.xml.XMLUtils;

public class MeshtasticReceiver extends BroadcastReceiver implements CotServiceRemote.CotEventListener {
    // constants
    private static final String TAG = "MeshtasticReceiver";
    private static NotificationManager mNotifyManager;
    private static NotificationCompat.Builder mBuilder;
    private static NotificationChannel mChannel;
    private static int id = Constants.NOTIFICATION_ID;
    private static int RECORDER_SAMPLERATE = Constants.AUDIO_SAMPLE_RATE;
    // shared prefs
    private static ProtectedSharedPreferences prefs = new ProtectedSharedPreferences(
            PreferenceManager.getDefaultSharedPreferences(MapView.getMapView().getContext())
    );
    private ProtectedSharedPreferences.Editor editor = prefs.edit();
    // audio playback
    private short playbackBuf[] = null;
    private AudioTrack track = null;
    private int samplesBufSize = 0;
    private boolean audioPermissionGranted = false;
    // chunking
    private final HashMap<Integer, byte[]> chunkMap = new HashMap<>();
    private boolean chunking = false;
    private int chunkSize = 0;
    private int chunkCount = 0;
    private static final int MAX_CHUNK_MAP_SIZE = 1000; // Prevent unbounded growth
    // misc
    private long c2 = 0;
    private int oldModemPreset;
    private String sender;
    // Meshtastic externl gps
    private final MeshtasticExternalGPS meshtasticExternalGPS;

    public MeshtasticReceiver(MeshtasticExternalGPS meshtasticExternalGPS) {
        this.meshtasticExternalGPS = meshtasticExternalGPS;
        int permissionCheck = ContextCompat.checkSelfPermission(MapView.getMapView().getContext(), Manifest.permission.RECORD_AUDIO);
        if (permissionCheck != PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "REC AUDIO DENIED");
        } else {
            this.audioPermissionGranted = true;
        }

        this.mNotifyManager = (NotificationManager) getMapView().getContext().getSystemService(NOTIFICATION_SERVICE);
        this.mChannel = new NotificationChannel("com.atakmap.android.meshtastic", "Meshtastic Notifications", NotificationManager.IMPORTANCE_DEFAULT); // correct Constant
        this.mChannel.setSound(null, null);
        this.mNotifyManager.createNotificationChannel(mChannel);

        Intent atakFrontIntent = new Intent();
        atakFrontIntent.setComponent(new ComponentName("com.atakmap.app.civ", "com.atakmap.app.ATAKActivity"));
        atakFrontIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        atakFrontIntent.putExtra("internalIntent", new Intent("com.atakmap.android.meshtastic.SHOW_PLUGIN"));
        PendingIntent appIntent = PendingIntent.getActivity(getMapView().getContext(), 0, atakFrontIntent, PendingIntent.FLAG_IMMUTABLE);

        mBuilder = new NotificationCompat.Builder(_mapView.getContext(), "com.atakmap.android.meshtastic");
        mBuilder.setContentTitle("Meshtastic File Transfer")
                .setContentText("Transfer in progress")
                .setSmallIcon(R.drawable.ic_launcher)
                .setAutoCancel(true)
                .setOngoing(false)
                .setContentIntent(appIntent);

        // codec2 recorder/playback - hardcoded to 700C for compatibility
        // NOTE: All devices must use the same codec mode for audio to work properly
        this.c2 = Codec2.create(Codec2.CODEC2_MODE_700C);
        if (c2 == 0) {
            Log.e(TAG, "Failed to create Codec2 with mode 700C");
        }
        this.samplesBufSize = Codec2.getSamplesPerFrame(c2);
        Log.d(TAG, "Codec2 initialized with mode 700C, samples per frame: " + samplesBufSize);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action == null) return;
        Log.d(TAG, "ACTION: " + action);
        switch (action) {
            case Constants.ACTION_MESH_CONNECTED: {
                String extraConnected = intent.getStringExtra(Constants.EXTRA_CONNECTED);
                boolean connected = extraConnected.equals(Constants.STATE_CONNECTED);
                Log.d(TAG, "Received ACTION_MESH_CONNECTED: " + extraConnected);
                if (connected) {
                    SharedPreferences.Editor editor = prefs.edit();
                    editor.putBoolean(PREF_PLUGIN_SHORTTURBO, false);
                    editor.apply();
                    boolean ret = MeshtasticMapComponent.reconnect();
                    if (ret) {
                        MeshtasticMapComponent.mConnectionState = MeshtasticMapComponent.ServiceConnectionState.CONNECTED;
                        MeshtasticMapComponent.mw.setIcon("green");
                    }
                } else {
                    MeshtasticMapComponent.mConnectionState = MeshtasticMapComponent.ServiceConnectionState.DISCONNECTED;
                    MeshtasticMapComponent.mw.setIcon("red");
                }
                break;
            }
            case Constants.ACTION_MESH_DISCONNECTED: {
                String extraConnected = intent.getStringExtra(Constants.EXTRA_DISCONNECTED);
                if (extraConnected == null) {
                    Log.d(TAG, "Received ACTION_MESH_DISCONNECTED: null");
                    return;
                }
                boolean connected = extraConnected.equals(Constants.STATE_DISCONNECTED);
                Log.d(TAG, "Received ACTION_MESH_DISCONNECTED: " + extraConnected);
                if (connected) {
                    MeshtasticMapComponent.mConnectionState = MeshtasticMapComponent.ServiceConnectionState.DISCONNECTED;
                    MeshtasticMapComponent.mw.setIcon("red");
                }
                break;
            }
            case Constants.ACTION_MESSAGE_STATUS:
                int id = intent.getIntExtra(Constants.EXTRA_PACKET_ID, 0);
                MessageStatus status = intent.getParcelableExtra(Constants.EXTRA_STATUS);
                Log.d(TAG, "Message Status ID: " + id + " Status: " + status);
                
                // Process ACK through AckManager
                AckManager.getInstance().processAck(id, status);
                
                // Keep legacy preference updates for backward compatibility
                if (prefs.getInt(Constants.PREF_PLUGIN_SWITCH_ID, 0) == id && status == MessageStatus.DELIVERED) {
                    editor.putBoolean(Constants.PREF_PLUGIN_SWITCH_ACK, false);
                    editor.apply();
                    Log.d(TAG, "Got ACK from Switch");
                } else if (prefs.getInt(Constants.PREF_PLUGIN_CHUNK_ID, 0) == id && status == MessageStatus.DELIVERED) {
                    // clear the ACK/ERR for the chunk
                    editor.putBoolean(Constants.PREF_PLUGIN_CHUNK_ACK, false);
                    editor.putBoolean(Constants.PREF_PLUGIN_CHUNK_ERR, false);
                    editor.apply();
                    Log.d(TAG, "Got DELIVERED from Chunk");
                } else if (prefs.getInt(Constants.PREF_PLUGIN_CHUNK_ID, 0) == id && status == MessageStatus.ERROR) {
                    editor.putBoolean(Constants.PREF_PLUGIN_CHUNK_ACK, false);
                    editor.putBoolean(Constants.PREF_PLUGIN_CHUNK_ERR, true);
                    editor.apply();
                    Log.d(TAG, "Got ERROR from Chunk");
                }
                break;
            case Constants.ACTION_RECEIVED_ATAK_FORWARDER:
            case Constants.ACTION_RECEIVED_ATAK_PLUGIN: {
                Thread thread = new Thread(() -> {
                    try {
                        receive(intent);
                    } catch (Exception e) {
                        e.printStackTrace();
                        return;
                    }
                });
                thread.setName("MeshtasticReceiver.Worker");
                thread.start();
                break;
            }
            case Constants.ACTION_ALERT_APP:
            case Constants.ACTION_TEXT_MESSAGE_APP:
                Log.d(TAG, "Got a meshtastic text message");
                DataPacket payload = intent.getParcelableExtra(Constants.EXTRA_PAYLOAD);
                if (payload == null) return;
                String message = new String(payload.getBytes());
                Log.d(TAG, "Message: " + message);
                Log.d(TAG, payload.toString());

                if (prefs.getBoolean(Constants.PREF_PLUGIN_VOICE, false)) {
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

                    CotDetail meshDetail = new CotDetail("__meshtastic");
                    cotDetail.addChild(meshDetail);

                    if (cotEvent.isValid()) {
                        CotMapComponent.getInternalDispatcher().dispatch(cotEvent);
                        if (prefs.getBoolean(Constants.PREF_PLUGIN_SERVER, false)) {
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

                    CotDetail meshDetail = new CotDetail("__meshtastic");
                    cotDetail.addChild(meshDetail);

                    if (cotEvent.isValid()) {
                        CotMapComponent.getInternalDispatcher().dispatch(cotEvent);
                    } else
                        Log.e(TAG, "cotEvent was not valid");
                }
                break;
            case Constants.ACTION_NODE_CHANGE:
                NodeInfo ni = null;
                try {
                    ni = intent.getParcelableExtra("com.geeksville.mesh.NodeInfo");
                    Log.d(TAG, "NodeInfo: " + ni);
                } catch (Exception e) {
                    e.printStackTrace();
                    return;
                }

                if (ni == null) {
                    Log.d(TAG, "NodeInfo was null");
                    return;
                } else if (ni.getUser() == null) {
                    Log.d(TAG, "getUser was null");
                    return;
                } else  if (ni.getPosition() == null) {
                    Log.d(TAG, "getPosition was null");
                    return;
                }

                if (prefs.getBoolean(Constants.PREF_PLUGIN_NOGPS, false)) {
                    if (ni.getPosition().getLatitude() == 0 && ni.getPosition().getLongitude() == 0) {
                        Log.d(TAG, "Ignoring NodeInfo with 0,0 GPS");
                        return;
                    }
                    Log.d(TAG, "NodeInfo GPS: " + ni.getPosition().getLatitude() + ", " + ni.getPosition().getLongitude() + ", Ignoring due to preferences");
                }

                String myId = MeshtasticMapComponent.getMyNodeID();
                if (myId == null) {
                    Log.d(TAG, "myId was null");
                    return;
                }
                boolean shouldUseMeshtasticExternalGPS = prefs.getBoolean(Constants.PREF_PLUGIN_EXTERNAL_GPS, false);
                if (shouldUseMeshtasticExternalGPS && ni.getUser().getId().equals(myId)) {
                    Log.d(TAG, "Sending self coordinates to network GPS");

                    meshtasticExternalGPS.updatePosition(ni.getPosition());
                }

                if (ni.getUser().getId().equals(myId) && prefs.getBoolean(Constants.PREF_PLUGIN_SELF, false)) {
                    Log.d(TAG, "Ignoring self");
                    return;
                }

                if (prefs.getBoolean(Constants.PREF_PLUGIN_TRACKER, false)) {
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
                        if (prefs.getBoolean(Constants.PREF_PLUGIN_SERVER, false)) {
                            CotMapComponent.getExternalDispatcher().dispatch(cotEvent);
                        }
                    } else
                        Log.e(TAG, "cotEvent was not valid");
                }
                break;
        }
    }

    public byte[] slice(byte[] array, int start, int end) {
        if (start < 0) {
            start = array.length + start;
        }
        if (end < 0) {
            end = array.length + end;
        }
        int length = end - start;
        byte[] result = new byte[length];
        for (int i = 0; i < length; i++) {
            result[i] = array[start + i];
        }
        return result;
    }

    public short[] append(short[] a, short[] b) {
        short[] result = new short[a.length + b.length];
        for (int i = 0; i < a.length; i++)
            result[i] = a[i];
        for (int i = 0; i < b.length; i++)
            result[a.length + i] = b[i];
        return result;
    }

    public static List<byte[]> extractChunks(byte[] byteArray, int chunkSize) {
        List<byte[]> chunks = new ArrayList<>();

        for (int i = 0; i < byteArray.length; i += chunkSize) {
            int endIndex = Math.min(i + chunkSize, byteArray.length);
            byte[] chunk = Arrays.copyOfRange(byteArray, i, endIndex);
            chunks.add(chunk);
        }

        return chunks;
    }
    private final ConcurrentLinkedQueue<short[]> playbackQueue = new ConcurrentLinkedQueue<>();
    private boolean isPlaying = false;

    public void playAudio(short[] audioData) {
        playbackQueue.add(audioData);
        processQueue();
    }

    private synchronized void processQueue() {
        if (isPlaying) return; // Prevent multiple simultaneous playbacks
        
        AudioTrack audioTrack = null;
        try {
            int minAudioBufSize = AudioRecord.getMinBufferSize(
                    RECORDER_SAMPLERATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
            );

            // Ensure buffer size is a proper multiple of samplesBufSize * 2
            int requiredSize = samplesBufSize * 2;
            if (minAudioBufSize % requiredSize != 0) {
                minAudioBufSize = ((minAudioBufSize / requiredSize) + 1) * requiredSize;
            }

            // for voice playback
            audioTrack = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(RECORDER_SAMPLERATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(minAudioBufSize)
                    .build();

            audioTrack.setVolume(AudioTrack.getMaxVolume());
            audioTrack.play();
            isPlaying = true;
            
            // Store reference for cleanup
            this.track = audioTrack;
            
            final AudioTrack finalTrack = audioTrack;
            new Thread(() -> {
                try {
                    while (!playbackQueue.isEmpty()) {
                        short[] audioData = playbackQueue.poll();
                        if (audioData == null) continue;
                        finalTrack.write(audioData, 0, audioData.length); // Blocking call
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error during playback: " + e.getMessage());
                } finally {
                    // Ensure playback is fully stopped and resources are released
                    try {
                        if (finalTrack != null) {
                            if (finalTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                                Log.d(TAG, "Stopping playback");
                                finalTrack.stop();
                            }
                            finalTrack.flush();
                            finalTrack.release();
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error releasing AudioTrack: " + e.getMessage());
                    } finally {
                        isPlaying = false;
                        this.track = null;
                    }
                }
            }).start();
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize AudioTrack: " + e.getMessage());
            isPlaying = false;
            if (audioTrack != null) {
                try {
                    audioTrack.release();
                } catch (Exception releaseError) {
                    Log.e(TAG, "Error releasing AudioTrack on initialization failure: " + releaseError.getMessage());
                }
            }
        }
    }

    protected void receive(Intent intent) {
        DataPacket payload = intent.getParcelableExtra(Constants.EXTRA_PAYLOAD);
        if (payload == null) return;
        int dataType = payload.getDataType();
        Log.v(TAG, "handleReceive(), dataType: " + dataType + " size: " + payload.getBytes().length);

        SharedPreferences.Editor editor = prefs.edit();

        if (dataType == Portnums.PortNum.ATAK_FORWARDER_VALUE) {
            byte[] raw = payload.getBytes();

            // codec2 frame, short-circuit the rest of the data processing
            if (audioPermissionGranted  && (raw[0] & 0xFF) == 0xC2) {
                Log.d(TAG, "Received codec2 frame");

                // skip 0xC2 from data and split into frames
                // 700C mode uses 8 bytes per frame
                int frameSize = Codec2.getBitsSize(c2);
                List<byte[]> frames = extractChunks(slice(raw, 1, raw.length), frameSize);
                Log.d(TAG, "Frames: " + frames.size() + ", frame size: " + frameSize);

                for (byte[] frame : frames) {
                    if (frame.length == frameSize) {
                        playbackBuf = new short[samplesBufSize];
                        Codec2.decode(c2, playbackBuf, frame);
                        playAudio(playbackBuf);
                    } else {
                        Log.w(TAG, "Skipping incomplete frame of size " + frame.length);
                    }
                }

                return;

            }

            String message = new String(payload.getBytes());
            // user must opt-in for SWITCH message
            if (message.startsWith("SWT") && prefs.getBoolean(Constants.PREF_PLUGIN_SWITCH, false)) {
                Log.d(TAG, "Received Switch message");

                // flag to indicate we're in file transfer mode
                editor.putBoolean(Constants.PREF_PLUGIN_FILE_TRANSFER, true);
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
                    // Rollback state on error
                    editor.putBoolean(Constants.PREF_PLUGIN_FILE_TRANSFER, false);
                    editor.apply();
                    return;
                }

                Log.d(TAG, "Config: " + c.toString());
                ConfigProtos.Config.DeviceConfig dc = c.getDevice();
                ConfigProtos.Config.LoRaConfig lc = c.getLora();
                oldModemPreset = lc.getModemPreset().getNumber();

                // set short/turbo for file transfer
                ConfigProtos.Config.Builder configBuilder = ConfigProtos.Config.newBuilder();
                AtomicReference<ConfigProtos.Config.LoRaConfig.Builder> loRaConfigBuilder = new AtomicReference<>(lc.toBuilder());
                AtomicReference<ConfigProtos.Config.LoRaConfig.ModemPreset> modemPreset = new AtomicReference<>(ConfigProtos.Config.LoRaConfig.ModemPreset.forNumber(ConfigProtos.Config.LoRaConfig.ModemPreset.SHORT_TURBO_VALUE));
                loRaConfigBuilder.get().setModemPreset(modemPreset.get());
                configBuilder.setLora(loRaConfigBuilder.get());
                boolean needReboot;

                // if not already in short/turbo mode, switch to it
                if (oldModemPreset != ConfigProtos.Config.LoRaConfig.ModemPreset.SHORT_TURBO_VALUE) {
                    ((Activity)MapView.getMapView().getContext()).runOnUiThread(() -> {
                        Toast.makeText(getMapView().getContext(), "Rebooting to Short/Turbo for file transfer", Toast.LENGTH_LONG).show();
                    });
                    needReboot = true;
                } else {
                    needReboot = false;
                }

                SharedPreferences.Editor finalEditor = editor;
                new Thread(() -> {
                    boolean shortTurboSet = false;
                    try {
                        // hopefully enough time to ACK the SWT command
                        Thread.sleep(2000);

                        // we gotta reboot to short/fast
                        if (needReboot) {
                            try {
                                // flag to indicate we are rebooting into short/fast
                                finalEditor.putBoolean(PREF_PLUGIN_SHORTTURBO, true);
                                finalEditor.apply();
                                shortTurboSet = true;
                                MeshtasticMapComponent.setConfig(configBuilder.build().toByteArray());
                                // wait for ourselves to switch to short/fast
                                while (prefs.getBoolean(PREF_PLUGIN_SHORTTURBO, false))
                                    Thread.sleep(1000);
                                shortTurboSet = false; // Successfully switched
                            } catch (InterruptedException e) {
                                e.printStackTrace();
                                // Reset flag if interrupted during reboot
                                if (shortTurboSet) {
                                    finalEditor.putBoolean(PREF_PLUGIN_SHORTTURBO, false);
                                    finalEditor.apply();
                                }
                                return;
                            }
                        }

                        // wait for file transfer to finish
                        while(prefs.getBoolean(Constants.PREF_PLUGIN_FILE_TRANSFER, false))
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
                        // Ensure state is cleaned up on interruption
                        if (shortTurboSet) {
                            finalEditor.putBoolean(PREF_PLUGIN_SHORTTURBO, false);
                            finalEditor.apply();
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Unexpected error in SWT handler thread", e);
                        // Ensure state is cleaned up on any error
                        finalEditor.putBoolean(Constants.PREF_PLUGIN_FILE_TRANSFER, false);
                        if (shortTurboSet) {
                            finalEditor.putBoolean(PREF_PLUGIN_SHORTTURBO, false);
                        }
                        finalEditor.apply();
                    }
                }).start();

            } else if (message.startsWith("MFT")) {
                // sender side, recv file transfer over
                Log.d(TAG, "Received File message completed");
                editor = prefs.edit();
                editor.putBoolean(Constants.PREF_PLUGIN_FILE_TRANSFER, false);
                editor.apply();
                
                // Signal file transfer completion
                FileTransferManager.getInstance().completeTransfer();
            } else if (message.startsWith("CHK")) {
                Log.d(TAG, "Received Chunked message");
                chunking = true;
                if (chunkSize == 0) {
                    try {
                        chunkSize = Integer.parseInt(message.split("_")[1]);
                        Log.d(TAG, "Chunk size: " + chunkSize);
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to parse chunk size", e);
                        // Reset chunking state on error
                        chunking = false;
                        chunkSize = 0;
                        return;
                    }
                }
                int chunk_hdr_size = String.format(Locale.US, "CHK_%d_", chunkSize).getBytes().length;
                byte[] chunk = new byte[payload.getBytes().length - chunk_hdr_size];
                try {
                    System.arraycopy(payload.getBytes(), chunk_hdr_size, chunk, 0, payload.getBytes().length - chunk_hdr_size);
                } catch (Exception e) {
                    e.printStackTrace();
                    Log.d(TAG, "Failed to copy first chunk");
                    // Don't continue processing if chunk copy failed
                    return;
                }

                // check if this chunk has already been received
                if (chunkMap.containsValue(chunk)) {
                    Log.d(TAG, "Chunk already received");
                    return;
                } else {
                    // Prevent unbounded growth - clear if too many chunks
                    if (chunkMap.size() >= MAX_CHUNK_MAP_SIZE) {
                        Log.w(TAG, "Chunk map exceeded maximum size, clearing");
                        chunkMap.clear();
                        chunking = false;
                        chunkSize = 0;
                        chunkCount = 0;
                        return;
                    }
                    chunkMap.put(Integer.valueOf(chunkCount++), chunk);
                }

                if(prefs.getBoolean(Constants.PREF_PLUGIN_FILE_TRANSFER, false)) {
                    // caclulate progress
                    //zi = (xi – min(x)) / (max(x) – min(x)) * 100
                    mBuilder.setProgress(100, (int) Math.floor((chunkMap.size() - 1) / (chunkSize - 1) * 100), false);
                    mNotifyManager.notify(id, mBuilder.build());
                }
            } else if (message.startsWith("END") && chunking) {
                Log.d(TAG, "Chunking");
                byte[] combined = new byte[chunkSize];

                int i = 0;
                boolean copyFailed = false;
                for (byte[] b : chunkMap.values()) {
                    try {
                        System.arraycopy(b, 0, combined, i, b.length);
                    } catch (Exception e) {
                        e.printStackTrace();
                        Log.d(TAG, "Failed to copy in chunking");
                        copyFailed = true;
                        break;
                    }
                    i += b.length;
                    Log.d(TAG, "" + i);
                }

                // done chunking clear accounting
                chunkSize = 0;
                chunking = false;
                chunkMap.clear();
                chunkCount = 0;
                
                // If copy failed, don't process the corrupted data
                if (copyFailed) {
                    Log.e(TAG, "Chunk assembly failed, discarding data");
                    return;
                }

                // this was a file transfer not chunks
                if (prefs.getBoolean(Constants.PREF_PLUGIN_FILE_TRANSFER, false)) {
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
                    DataPacket dp = new DataPacket(sender, new byte[]{'M', 'F', 'T'}, Portnums.PortNum.ATAK_FORWARDER_VALUE, DataPacket.ID_LOCAL, System.currentTimeMillis(), 0, MessageStatus.UNKNOWN, getHopLimit(), getChannelIndex(), getWantsAck());
                    MeshtasticMapComponent.sendToMesh(dp);
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                        Log.d(TAG, "MFT interrupted");
                    }

                    //receive side, file transfer over
                    editor = prefs.edit();
                    editor.putBoolean(Constants.PREF_PLUGIN_FILE_TRANSFER, false);
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
                    TransformerFactory tf = XMLUtils.getTransformerFactory();
                    Transformer transformer = tf.newTransformer();
                    transformer.transform(exiSource, result);
                    CotEvent cotEvent = CotEvent.parse(writer.toString());

                    if (cotEvent.isValid()) {
                        Log.d(TAG, "Chunked CoT Received");
                        CotMapComponent.getInternalDispatcher().dispatch(cotEvent);
                        if (prefs.getBoolean(Constants.PREF_PLUGIN_SERVER, false)) {
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
                    TransformerFactory tf = XMLUtils.getTransformerFactory();
                    Transformer transformer = tf.newTransformer();
                    transformer.transform(exiSource, result);
                    CotEvent cotEvent = CotEvent.parse(writer.toString());
                    if (cotEvent.isValid()) {
                        CotMapComponent.getInternalDispatcher().dispatch(cotEvent);
                        if (prefs.getBoolean(Constants.PREF_PLUGIN_SERVER, false)) {
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
                        if (prefs.getBoolean(Constants.PREF_PLUGIN_SERVER, false)) {
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

                    if (prefs.getBoolean(Constants.PREF_PLUGIN_VOICE, false)) {
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
                        if (prefs.getBoolean(Constants.PREF_PLUGIN_SERVER, false)) {
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

                    if (prefs.getBoolean(Constants.PREF_PLUGIN_VOICE, false)) {
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

                    CotDetail meshDetail = new CotDetail("__meshtastic");
                    cotDetail.addChild(meshDetail);

                    CotEvent cotEvent = new CotEvent();
                    cotEvent.setDetail(cotDetail);
                    cotEvent.setUID("GeoChat." + deviceCallsign + getMapView().getSelfMarker().getUID() + msgId);
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
                Log.e(TAG, "Could not parse TAK packet", e);
            }
        }
    }

    public static boolean getWantsAck() {
        try {
            return prefs.getBoolean(Constants.PREF_PLUGIN_WANT_ACK, true);
        } catch (Exception e) {
            e.printStackTrace();
            return true;
        }
    }

    public static int getHopLimit() {
        try {
            int hopLimit = prefs.getInt(Constants.PREF_PLUGIN_HOP_LIMIT, 3);
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
            int channel = prefs.getInt(Constants.PREF_PLUGIN_CHANNEL, 0);
            Log.d(TAG, "Channel: " + channel);
            return channel;
        } catch (Exception e) {
            e.printStackTrace();
            return 0;
        }
    }

    @Override
    public void onCotEvent(CotEvent cotEvent, Bundle bundle) {

        if (prefs.getBoolean(Constants.PREF_PLUGIN_FROM_SERVER, false)) {
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

                    dp = new DataPacket(DataPacket.ID_BROADCAST, tak_packet.build().toByteArray(), Portnums.PortNum.ATAK_PLUGIN_VALUE, DataPacket.ID_LOCAL, System.currentTimeMillis(), 0, MessageStatus.UNKNOWN, hopLimit, channel, getWantsAck());
                    if (MeshtasticMapComponent.getMeshService() != null)
                        MeshtasticMapComponent.getMeshService().sendToMesh(dp);
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

                            dp = new DataPacket(DataPacket.ID_BROADCAST, tak_packet.build().toByteArray(), Portnums.PortNum.ATAK_PLUGIN_VALUE, DataPacket.ID_LOCAL, System.currentTimeMillis(), 0, MessageStatus.UNKNOWN, hopLimit, channel, getWantsAck());
                            if (MeshtasticMapComponent.getMeshService() != null)
                                MeshtasticMapComponent.getMeshService().sendToMesh(dp);
                        }
                    }
                }
            }
        }
    }
    
    /**
     * Reinitialize codec with new settings
     */
    public void reinitializeCodec() {
        // Destroy old codec instance if it exists
        if (c2 != 0) {
            try {
                Codec2.destroy(c2);
            } catch (Exception e) {
                Log.e(TAG, "Error destroying old Codec2 instance", e);
            }
        }
        
        // Create new codec - hardcoded to 700C for compatibility
        this.c2 = Codec2.create(Codec2.CODEC2_MODE_700C);
        if (c2 == 0) {
            Log.e(TAG, "Failed to create Codec2 with mode 700C");
        }
        this.samplesBufSize = Codec2.getSamplesPerFrame(c2);
        Log.d(TAG, "Codec2 reinitialized with mode 700C, samples per frame: " + samplesBufSize);
    }
    
    /**
     * Clean up resources when the receiver is unregistered
     */
    public void cleanup() {
        // Destroy Codec2 instance
        if (c2 != 0) {
            try {
                Codec2.destroy(c2);
                c2 = 0;
            } catch (Exception e) {
                Log.e(TAG, "Error destroying Codec2 instance", e);
            }
        }
        
        // Stop and release audio track if playing
        if (track != null) {
            try {
                if (track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                    track.stop();
                }
                track.release();
                track = null;
            } catch (Exception e) {
                Log.e(TAG, "Error releasing audio track", e);
            }
        }
        
        isPlaying = false;
    }
}
