
package com.atakmap.android.meshtastic;

import static android.content.Context.NOTIFICATION_SERVICE;
import static com.atakmap.android.maps.MapView.getMapView;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.preference.PreferenceManager;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;


import com.atakmap.android.data.URIContentManager;
import com.atakmap.android.dropdown.DropDownMapComponent;
import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.meshtastic.plugin.R;
import com.atakmap.app.preferences.ToolsPreferenceFragment;
import com.atakmap.comms.CotServiceRemote;
import com.atakmap.coremap.filesystem.FileSystemUtils;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.cot.event.CotEvent;
import com.atakmap.coremap.cot.event.CotDetail;
import com.atakmap.comms.CommsMapComponent;

import com.geeksville.mesh.MeshProtos;
import com.geeksville.mesh.MeshUser;
import com.geeksville.mesh.MessageStatus;
import com.geeksville.mesh.ATAKProtos;
import com.geeksville.mesh.MyNodeInfo;
import com.geeksville.mesh.NodeInfo;
import com.geeksville.mesh.Portnums;
import com.geeksville.mesh.DataPacket;
import com.geeksville.mesh.IMeshService;

import com.google.protobuf.ByteString;
import com.siemens.ct.exi.core.EXIFactory;
import com.siemens.ct.exi.core.helpers.DefaultEXIFactory;
import com.siemens.ct.exi.main.api.sax.EXIResult;

import org.xml.sax.InputSource;
import org.xml.sax.XMLReader;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;


public class MeshtasticMapComponent extends DropDownMapComponent
        implements CommsMapComponent.PreSendProcessor,
        SharedPreferences.OnSharedPreferenceChangeListener,
        CotServiceRemote.ConnectionListener {
    private static final String TAG = "MeshtasticMapComponent";
    private Context pluginContext;
    @Override
    public void onCotServiceConnected(Bundle bundle) {

    }

    @Override
    public void onCotServiceDisconnected() {

    }

    public enum ServiceConnectionState {
        DISCONNECTED,
        CONNECTED
    }
    private MeshtasticDropDownReceiver ddr;
    private static IMeshService mMeshService;
    private static ServiceConnection mServiceConnection;
    private static Intent mServiceIntent;
    public static ServiceConnectionState mConnectionState = ServiceConnectionState.DISCONNECTED;
    public static final String PACKAGE_NAME = "com.geeksville.mesh";
    public static final String CLASS_NAME = "com.geeksville.mesh.service.MeshService";
    public static final String ACTION_MESH_CONNECTED = "com.geeksville.mesh.MESH_CONNECTED";
    public static final String ACTION_MESH_DISCONNECTED = "com.geeksville.mesh.MESH_DISCONNECTED";
    public static final String ACTION_RECEIVED_ATAK_FORWARDER = "com.geeksville.mesh.RECEIVED.ATAK_FORWARDER";
    public static final String ACTION_RECEIVED_ATAK_PLUGIN = "com.geeksville.mesh.RECEIVED.ATAK_PLUGIN";
    public static final String ACTION_RECEIVED_NODEINFO_APP = "com.geeksville.mesh.RECEIVED.NODEINFO_APP";
    public static final String ACTION_RECEIVED_POSITION_APP = "com.geeksville.mesh.RECEIVED.POSITION_APP";
    public static final String ACTION_TEXT_MESSAGE_APP = "com.geeksville.mesh.RECEIVED.TEXT_MESSAGE_APP";
    public static final String ACTION_ALERT_APP = "com.geeksville.mesh.RECEIVED.ALERT_APP";

    public static final String ACTION_NODE_CHANGE = "com.geeksville.mesh.NODE_CHANGE";
    public static final String ACTION_MESSAGE_STATUS = "com.geeksville.mesh.MESSAGE_STATUS";
    public static final String EXTRA_CONNECTED = "com.geeksville.mesh.Connected";
    public static final String EXTRA_DISCONNECTED = "com.geeksville.mesh.disconnected";
    public static final String EXTRA_PERMANENT = "com.geeksville.mesh.Permanent";
    public static final String EXTRA_PAYLOAD = "com.geeksville.mesh.Payload";
    public static final String EXTRA_NODEINFO = "com.geeksville.mesh.NodeInfo";
    public static final String EXTRA_PACKET_ID = "com.geeksville.mesh.PacketId";
    public static final String EXTRA_STATUS = "com.geeksville.mesh.Status";
    public static final String STATE_CONNECTED = "CONNECTED";
    public static final String STATE_DISCONNECTED = "DISCONNECTED";
    public static final String STATE_DEVICE_SLEEP = "DEVICE_SLEEP";
    public static final String ACTION_CONFIG_RATE = "com.atakmap.android.meshtastic.CONFIG";
    public static MeshtasticWidget mw;
    private MeshtasticReceiver mr;
    private MeshtasticSender meshtasticSender;
    private static NotificationManager mNotifyManager;
    private static NotificationCompat.Builder mBuilder;
    private static NotificationChannel mChannel;
    private static int NotificationId = 42069;
    private static SharedPreferences prefs;
    private static SharedPreferences.Editor editor;

    public static void sendToMesh(DataPacket dp) {
        try {
            if (mMeshService != null)
                mMeshService.send(dp);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    public static List<byte[]> divideArray(byte[] source, int chunksize) {

        List<byte[]> result = new ArrayList<>();
        int start = 0;
        while (start < source.length) {
            int end = Math.min(source.length, start + chunksize);
            try {
                result.add(Arrays.copyOfRange(source, start, end));
            } catch (Exception e) {
                e.printStackTrace();
                Log.d(TAG, "Array copy failed in divideArray");
            }
            start += chunksize;
        }

        return result;
    }

    public static boolean sendFile(File f) {

        byte[] fileBytes;
        try {
            fileBytes = FileSystemUtils.read(f);
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }

        int chunkSize = 220;
        int progress = 0;

        List<byte[]> chunkList = divideArray(fileBytes, chunkSize);

        int chunks = (int) Math.floor(fileBytes.length / chunkSize);
        chunks++;
        Log.d(TAG, "Sending " + chunks);
        byte[] chunk_hdr = String.format(Locale.US, "CHK_%d_", fileBytes.length).getBytes();
        int i = 0;

        HashMap<String, byte[]> chunkMap = new HashMap<>();
        for (byte[] c : chunkList) {
            byte[] combined = new byte[chunk_hdr.length + c.length];
            try {
                System.arraycopy(chunk_hdr, 0, combined, 0, chunk_hdr.length);
                System.arraycopy(c, 0, combined, chunk_hdr.length, c.length);
            } catch (Exception e) {
                e.printStackTrace();
                return false;
            }
            try {
                // send out 1 chunk
                DataPacket dp;
                i = mMeshService.getPacketId();
                Log.d(TAG, "Chunk ID: " + i);
                chunkMap.put(String.valueOf(i), combined);
                editor.putInt("plugin_meshtastic_chunk_id", i);
                editor.putBoolean("plugin_meshtastic_chunk_ACK", true);
                editor.apply();

                INNER:
                for (int j=0; j<1; j++) {
                    dp = new DataPacket(DataPacket.ID_BROADCAST, combined, Portnums.PortNum.ATAK_FORWARDER_VALUE, DataPacket.ID_LOCAL, System.currentTimeMillis(), i, MessageStatus.UNKNOWN, 3, prefs.getInt("plugin_meshtastic_channel", 0));
                    mMeshService.send(dp);
                    while (prefs.getBoolean("plugin_meshtastic_chunk_ACK", false)) {
                        try {
                            Thread.sleep(250);
                            if (prefs.getBoolean("plugin_meshtastic_chunk_ERR", false)) {
                                Log.d(TAG, "Chunk ERR received, retransmitting message ID: " + i);
                                j=0;
                                break INNER;
                            }
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
                }

                // caclulate progress
                //zi = (xi – min(x)) / (max(x) – min(x)) * 100
                mBuilder.setProgress(100, (int) Math.floor((++progress - 1) / (chunkList.size() - 1) * 100), false);
                mNotifyManager.notify(NotificationId, mBuilder.build());
            } catch (RemoteException e) {
                e.printStackTrace();
                return false;
            }
        }
        // When the loop is finished, updates the notification
        mBuilder.setContentText("Transfer complete")
                // Removes the progress bar
                .setProgress(0,0,false);
        mNotifyManager.notify(NotificationId, mBuilder.build());

        try {
            // We're done chunking
            DataPacket dp = new DataPacket(DataPacket.ID_BROADCAST, new byte[]{'E', 'N', 'D'}, Portnums.PortNum.ATAK_FORWARDER_VALUE, DataPacket.ID_LOCAL, System.currentTimeMillis(), 0, MessageStatus.UNKNOWN, 3, prefs.getInt("plugin_meshtastic_channel", 0));
            if (mMeshService != null)
                mMeshService.send(dp);
        } catch (RemoteException e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }

    public static void setOwner(MeshUser meshUser) {
        try {
            if (mMeshService != null)
                mMeshService.setOwner(meshUser);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }
    public static void setChannel(byte[] channel) {
        try {
            if (mMeshService != null)
                mMeshService.setChannel(channel);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    public static void setConfig(byte[] config) {
        try {
            if (mMeshService != null)
                mMeshService.setConfig(config);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    public static byte[] getChannelSet() {
        try {
            if (mMeshService != null)
                return mMeshService.getChannelSet();
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        return null;
    }

    public static byte[] getConfig() {
        try {
            if (mMeshService != null)
                return mMeshService.getConfig();
        } catch (RemoteException e) {
            e.printStackTrace();
            Log.d(TAG, "getConfig failed");
        }
        return null;
    }

    public static List<NodeInfo> getNodes() {
        try {
            if (mMeshService != null)
                return mMeshService.getNodes();
        } catch (Exception e) {
            e.printStackTrace();
            Log.d(TAG, "getNodes failed");
        }
        return null;
    }

    public static MyNodeInfo getMyNodeInfo() {
        try {
            if (mMeshService != null)
                return mMeshService.getMyNodeInfo();
        } catch (RemoteException e) {
            e.printStackTrace();
            Log.d(TAG, "getMyNodeInfo failed");
        }
        return null;
    }

    public static String getMyNodeID() {
        try {
            if (mMeshService != null)
                return mMeshService.getMyId();
        } catch (RemoteException e) {
            e.printStackTrace();
            Log.d(TAG, "getMyNodeID failed");
        }
        return "";
    }
    public static IMeshService getMeshService() {
        return mMeshService;
    }
    @Override
    public void processCotEvent(CotEvent cotEvent, String[] strings) {

        Log.d(TAG, "processCotEvent");

        CotDetail cotDetail = cotEvent.getDetail();
        if (cotDetail.getChild("__meshtastic") != null) {
            Log.d(TAG, "Meshtastic message, don't forward");
            return;
        }

        if (mConnectionState == ServiceConnectionState.DISCONNECTED) {
            Log.d(TAG, "Service not connected");
            return;
        } else if (prefs.getBoolean("plugin_meshtastic_file_transfer", false)) {
            Log.d(TAG, "File transfer in progress");
            return;
        }

        final DataPacket[] dp = new DataPacket[1];

        int hopLimit = mr.getHopLimit();
        
        int channel = mr.getChannelIndex();

        Log.d(TAG, cotEvent.toString());
        Log.d(TAG, cotDetail.toString());

        int eventType = -1;
        double divisor = 1e-7;
        XmlPullParserFactory factory = null;
        XmlPullParser xpp = null;
        String callsign = null;
        String deviceCallsign = null;
        double alt = getMapView().getSelfMarker().getPoint().getAltitude();
        double lat = getMapView().getSelfMarker().getPoint().getLatitude();
        double lng = getMapView().getSelfMarker().getPoint().getLongitude();
        if (lat == 0 && lng == 0) {
            return;
        } else {
            Log.d(TAG, "callsign: " + getMapView().getDeviceCallsign());
            Log.d(TAG, "device callsign: " + getMapView().getSelfMarker().getUID());
            Log.d(TAG, String.format("ALT:%f LAT:%f LNG:%f", alt, lat, lng));
        }

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
/*
        if (cotEvent.getUID().startsWith("!") && cotEvent.getType().equals("a-f-G-E-S")) {
            Log.d(TAG, "Don't forward Meshtastic Nodes");
            return;
        } else */if (cotEvent.getUID().equals(getMapView().getSelfMarker().getUID())) {
            // self PLI report
            /*
            <?xml version='1.0' encoding='UTF-8' standalone='yes'?>
            <event version='2.0' uid='ANDROID-e612f0e922b56a63' type='a-f-G-U-C' time='2024-02-06T19:27:18.246Z' start='2024-02-06T19:27:18.246Z' stale='2024-02-06T19:33:33.246Z' how='h-e'>
            <point lat='8.8813837' lon='-3.570282' hae='9999999.0' ce='9999999.0' le='9999999.0' />
            <detail>
                <takv os='34' version='4.8.1.5 (475f848f).1675356844-CIV' device='GOOGLE PIXEL 8' platform='ATAK-CIV'/>
                <contact endpoint='0.0.0.0:4242:tcp' phone='+16802809647' callsign='FALKE'/>
                <uid Droid='FALKE'/>
                <precisionlocation altsrc='???' geopointsrc='USER'/>
                <__group role='Team Member' name='Cyan'/>
                <status battery='100'/>
                <track course='45.77672420041893' speed='0.0'/>
            </detail>
            </event>
             */
            Log.d(TAG, "Sending self marker PLI to Meshtastic");

            int battery = 0, course = 0, speed = 0;
            String role = null, name = null;

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

            dp[0] = new DataPacket(DataPacket.ID_BROADCAST, tak_packet.build().toByteArray(), Portnums.PortNum.ATAK_PLUGIN_VALUE, DataPacket.ID_LOCAL, System.currentTimeMillis(), 0, MessageStatus.UNKNOWN, hopLimit, channel);
            try {
                if (mMeshService != null)
                    mMeshService.send(dp[0]);
            } catch (RemoteException e) {
                throw new RuntimeException(e);
            }
        // GeoChat All Chat Rooms
        } else if (cotEvent.getType().equalsIgnoreCase("b-t-f") && cotEvent.getUID().contains("All Chat Rooms")) {
            Log.d(TAG, "Sending All Chat Rooms to Meshtastic");
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
            String message = null;
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

            dp[0] = new DataPacket(DataPacket.ID_BROADCAST, tak_packet.build().toByteArray(), Portnums.PortNum.ATAK_PLUGIN_VALUE, DataPacket.ID_LOCAL, System.currentTimeMillis(), 0, MessageStatus.UNKNOWN, hopLimit, channel);
            try {
                if (mMeshService != null)
                    mMeshService.send(dp[0]);
            } catch (RemoteException e) {
                throw new RuntimeException(e);
            }

        } else if (cotEvent.getType().equalsIgnoreCase("b-t-f")) {
            Log.d(TAG, "Sending DM Chat to Meshtastic");
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
            String message = null;
            String to = null;
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
                                if (xpp.getAttributeName(i).equalsIgnoreCase("id"))
                                    to = xpp.getAttributeValue(i);
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

            if (to == null) return;

            ATAKProtos.Contact.Builder contact = ATAKProtos.Contact.newBuilder();
            contact.setCallsign(callsign);
            contact.setDeviceCallsign(deviceCallsign);

            ATAKProtos.GeoChat.Builder geochat = ATAKProtos.GeoChat.newBuilder();
            geochat.setMessage(message);
            geochat.setTo(to);

            ATAKProtos.TAKPacket.Builder tak_packet = ATAKProtos.TAKPacket.newBuilder();
            tak_packet.setContact(contact);
            tak_packet.setChat(geochat);

            Log.d(TAG, "Total wire size for TAKPacket: " + tak_packet.build().toByteArray().length);
            Log.d(TAG, "Sending: " + tak_packet.build().toString());

            // if "to" starts with !, its probably a meshtastic ID, so don't send it to ^all but the actual ID
            if (to.startsWith("!")) {
                Log.d(TAG, "Sending to Meshtastic ID: " + to);
                dp[0] = new DataPacket(to, MeshProtos.Data.newBuilder().setPayload(ByteString.copyFrom(message.getBytes(StandardCharsets.UTF_8))).build().toByteArray(),Portnums.PortNum.TEXT_MESSAGE_APP_VALUE, DataPacket.ID_LOCAL, System.currentTimeMillis(), 0, MessageStatus.UNKNOWN, hopLimit, channel);
            } else {
                Log.d(TAG, "Sending to ^all");
                dp[0] = new DataPacket(DataPacket.ID_BROADCAST, tak_packet.build().toByteArray(), Portnums.PortNum.ATAK_PLUGIN_VALUE, DataPacket.ID_LOCAL, System.currentTimeMillis(), 0, MessageStatus.UNKNOWN, hopLimit, channel);
            }
            try {
                if (mMeshService != null)
                    mMeshService.send(dp[0]);
            } catch (RemoteException e) {
                throw new RuntimeException(e);
            }

        } else {
            if (prefs.getBoolean("plugin_meshtastic_plichat_only", false)) {
                Log.d(TAG, "PLI/Chat Only");
                return;
            } else if (prefs.getBoolean("plugin_meshtastic_chunking", false)) {
                Log.d(TAG, "Chunking in progress");
                return;
            }

            editor.putBoolean("plugin_meshtastic_chunking", true);
            editor.apply();

            new Thread(() -> {
                Log.d(TAG, "Sending Chunks");

                byte[] cotAsBytes;

                try {
                    EXIFactory exiFactory = DefaultEXIFactory.newInstance();
                    ByteArrayOutputStream osEXI = new ByteArrayOutputStream();
                    EXIResult exiResult = new EXIResult(exiFactory);
                    exiResult.setOutputStream(osEXI);
                    SAXParserFactory saxParserFactory = SAXParserFactory.newInstance();
                    SAXParser newSAXParser = saxParserFactory.newSAXParser();
                    XMLReader xmlReader = newSAXParser.getXMLReader();
                    xmlReader.setContentHandler(exiResult.getHandler());
                    InputSource stream = new InputSource(new StringReader(cotEvent.toString()));
                    xmlReader.parse(stream); // parse XML input
                    cotAsBytes = osEXI.toByteArray();
                    osEXI.close();
                } catch (Exception e) {
                    e.printStackTrace();
                    return;
                }

                Log.d(TAG, "Size: " + cotAsBytes.length);

                if (cotAsBytes.length < 236) {
                    Log.d(TAG, "Small send");
                    dp[0] = new DataPacket(DataPacket.ID_BROADCAST, cotAsBytes, Portnums.PortNum.ATAK_FORWARDER_VALUE, DataPacket.ID_LOCAL, System.currentTimeMillis(), 0, MessageStatus.UNKNOWN, hopLimit, channel);
                    try {
                        if (mMeshService != null)
                            mMeshService.send(dp[0]);
                    } catch (RemoteException e) {
                        e.printStackTrace();
                    }
                    return;
                }

                int chunkSize = 220;
                List<byte[]> chunkList = divideArray(cotAsBytes, chunkSize);
                final AtomicInteger[] i = {new AtomicInteger()};
                int chunks = (int) Math.floor(cotAsBytes.length / chunkSize);
                chunks++;
                Log.d(TAG, "Sending " + chunks);

                byte[] chunk_hdr = String.format(Locale.US, "CHK_%d_", cotAsBytes.length).getBytes();
                HashMap<String, byte[]> chunkMap = new HashMap<>();

                for (byte[] c : chunkList) {
                    byte[] combined = new byte[chunk_hdr.length + c.length];
                    try {
                        System.arraycopy(chunk_hdr, 0, combined, 0, chunk_hdr.length);
                        System.arraycopy(c, 0, combined, chunk_hdr.length, c.length);
                    } catch (Exception e) {
                        e.printStackTrace();
                        return;
                    }

                    try {
                        // send out 1 chunk
                        i[0].set(mMeshService.getPacketId());
                        Log.d(TAG, "Chunk ID: " + i[0]);
                        chunkMap.put(String.valueOf(i[0].get()), combined);
                        editor.putInt("plugin_meshtastic_chunk_id", i[0].get());
                        editor.putBoolean("plugin_meshtastic_chunk_ACK", true);
                        editor.apply();

                        INNER:
                        for (int j = 0; j < 1; j++) {
                            dp[0] = new DataPacket(DataPacket.ID_BROADCAST, combined, Portnums.PortNum.ATAK_FORWARDER_VALUE, DataPacket.ID_LOCAL, System.currentTimeMillis(), i[0].get(), MessageStatus.UNKNOWN, hopLimit, channel);
                            mMeshService.send(dp[0]);
                            while (prefs.getBoolean("plugin_meshtastic_chunk_ACK", false)) {
                                try {
                                    Thread.sleep(250);
                                    if (prefs.getBoolean("plugin_meshtastic_chunk_ERR", false)) {
                                        Log.d(TAG, "Chunk ERR received, retransmitting message ID: " + i[0]);
                                        j = 0;
                                        break INNER;
                                    }
                                } catch (Exception e) {
                                    e.printStackTrace();
                                }
                            }
                        }
                    } catch (RemoteException e) {
                        e.printStackTrace();
                        return;
                    }
                }
                // We're done chunking
                dp[0] = new DataPacket(DataPacket.ID_BROADCAST, new byte[]{'E', 'N', 'D'}, Portnums.PortNum.ATAK_FORWARDER_VALUE, DataPacket.ID_LOCAL, System.currentTimeMillis(), 0, MessageStatus.UNKNOWN, 3, prefs.getInt("plugin_meshtastic_channel", 0));
                if (mMeshService != null) {
                    try {
                        mMeshService.send(dp[0]);
                    } catch (RemoteException e) {
                        e.printStackTrace();
                    }
                }
                editor.putBoolean("plugin_meshtastic_chunking", false);
                editor.apply();
            }).start();
        }
    }
    public void onCreate(final Context context, Intent intent, MapView view) {

        CommsMapComponent.getInstance().registerPreSendProcessor(this);
        context.setTheme(R.style.ATAKPluginTheme);
        pluginContext = context;

        mNotifyManager =
                (NotificationManager) view.getContext()
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
        PendingIntent appIntent = PendingIntent.getActivity(view.getContext(), 0, atakFrontIntent, PendingIntent.FLAG_IMMUTABLE);

        mBuilder = new NotificationCompat.Builder(pluginContext, "com.atakmap.android.meshtastic");
        mBuilder.setContentTitle("Meshtastic File Transfer")
                .setContentText("Transfer in progress")
                .setSmallIcon(R.drawable.ic_launcher)
                .setAutoCancel(true)
                .setOngoing(false)
                .setContentIntent(appIntent);

        Log.d(TAG, "registering the plugin filter");
        ddr = new MeshtasticDropDownReceiver(view, context);
        AtakBroadcast.DocumentedIntentFilter ddFilter = new AtakBroadcast.DocumentedIntentFilter();
        ddFilter.addAction(MeshtasticDropDownReceiver.SHOW_PLUGIN);
        registerDropDownReceiver(ddr, ddFilter);

        prefs = PreferenceManager.getDefaultSharedPreferences(MapView.getMapView().getContext());
        editor = prefs.edit();
        editor.putBoolean("plugin_meshtastic_file_transfer", false);
        editor.putBoolean("plugin_meshtastic_chunking", false);
        editor.apply();
        prefs.registerOnSharedPreferenceChangeListener(this);

        mr = new MeshtasticReceiver();
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(ACTION_RECEIVED_ATAK_FORWARDER);
        intentFilter.addAction("com.geeksville.mesh.RECEIVED.257");
        intentFilter.addAction(ACTION_RECEIVED_ATAK_PLUGIN);
        intentFilter.addAction("com.geeksville.mesh.RECEIVED.72");
        intentFilter.addAction(ACTION_NODE_CHANGE);
        intentFilter.addAction(ACTION_MESH_CONNECTED);
        intentFilter.addAction(ACTION_MESH_DISCONNECTED);
        intentFilter.addAction(ACTION_RECEIVED_NODEINFO_APP);
        intentFilter.addAction(ACTION_RECEIVED_POSITION_APP);
        intentFilter.addAction(ACTION_MESSAGE_STATUS);
        intentFilter.addAction(ACTION_TEXT_MESSAGE_APP);

        view.getContext().registerReceiver(mr, intentFilter, Context.RECEIVER_EXPORTED);

        CotServiceRemote cotService = new CotServiceRemote();
        cotService.setCotEventListener(mr);
        cotService.connect(this);

        mServiceIntent = new Intent();
        mServiceIntent.setClassName(PACKAGE_NAME, CLASS_NAME);

        URIContentManager.getInstance().registerSender(meshtasticSender = new MeshtasticSender(view, pluginContext));

        mServiceConnection = new ServiceConnection() {
            public void onServiceConnected(ComponentName className, IBinder service) {
                Log.v(TAG, "Service connected");
                mMeshService = IMeshService.Stub.asInterface(service);
                mConnectionState = ServiceConnectionState.CONNECTED;
                mw.setIcon("green");
            }

            public void onServiceDisconnected(ComponentName className) {
                Log.e(TAG, "Service disconnected");
                mMeshService = null;
                mConnectionState = ServiceConnectionState.DISCONNECTED;
                mw.setIcon("red");
            }
        };
        
        boolean ret = view.getContext().bindService(mServiceIntent, mServiceConnection, Context.BIND_AUTO_CREATE);
        if (!ret) {
            Toast.makeText(getMapView().getContext(), "Failed to bind to Meshtastic IMeshService", Toast.LENGTH_LONG).show();
        }

        mw = new MeshtasticWidget(context, view);

        ToolsPreferenceFragment.register(
                new ToolsPreferenceFragment.ToolPreference(
                        pluginContext.getString(R.string.preferences_title),
                        pluginContext.getString(R.string.preferences_summary),
                        pluginContext.getString(R.string.meshtastic_preferences),
                        pluginContext.getResources().getDrawable(R.drawable.ic_launcher),
                        new PluginPreferencesFragment(
                                pluginContext)));
    }
    public static boolean reconnect() throws RemoteException {
        boolean ret = getMapView().getContext().bindService(mServiceIntent, mServiceConnection, Context.BIND_AUTO_CREATE);
        if (!ret) {
            Toast.makeText(getMapView().getContext(), "Failed to bind to Meshtastic IMeshService", Toast.LENGTH_LONG).show();
        }
        return ret;
    }
    @Override
    protected void onDestroyImpl(Context context, MapView view) {
        view.getContext().unbindService(mServiceConnection);
        view.getContext().unregisterReceiver(mr);
        mw.destroy();
        prefs.unregisterOnSharedPreferenceChangeListener(this);
        ToolsPreferenceFragment.unregister(pluginContext.getString(R.string.preferences_title));
        URIContentManager.getInstance().unregisterSender(meshtasticSender);


    }
    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (key == null) return;
        if (FileSystemUtils.isEquals(key, "plugin_meshtastic_rate_value")) {
            String rate = prefs.getString("plugin_meshtastic_rate_value", "0");
            Log.d(TAG, "Rate: " + rate);
            editor.putString("locationReportingStrategy", "Constant");
            editor.putString("constantReportingRateUnreliable", rate);
            editor.putString("constantReportingRateReliable", rate);
            editor.apply();
        }
    }
}
