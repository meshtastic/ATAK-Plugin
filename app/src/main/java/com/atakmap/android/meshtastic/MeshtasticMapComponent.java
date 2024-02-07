
package com.atakmap.android.meshtastic;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Environment;
import android.os.IBinder;
import android.os.RemoteException;
import android.widget.Toast;

import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.AbstractMapComponent;
import com.atakmap.android.meshtastic.plugin.R;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.cot.event.CotEvent;
import com.atakmap.coremap.cot.event.CotDetail;
import com.atakmap.comms.CommsMapComponent;

import com.geeksville.mesh.MessageStatus;
import com.geeksville.mesh.ATAKProtos;
import com.geeksville.mesh.Portnums;
import com.geeksville.mesh.DataPacket;
import com.geeksville.mesh.IMeshService;

import com.paulmandal.atak.libcotshrink.pub.api.CotShrinker;
import com.paulmandal.atak.libcotshrink.pub.api.CotShrinkerFactory;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public class MeshtasticMapComponent extends AbstractMapComponent implements CommsMapComponent.PreSendProcessor {

    private static final String TAG = "MeshtasticMapComponent";

    private Context pluginContext;
    public static CotShrinkerFactory cotShrinkerFactory = new CotShrinkerFactory();
    public static CotShrinker cotShrinker = cotShrinkerFactory.createCotShrinker();

    public enum ServiceConnectionState {
        DISCONNECTED,
        CONNECTED
    }

    private static IMeshService mMeshService;
    private static ServiceConnection mServiceConnection;

    private static Intent mServiceIntent;

    public static ServiceConnectionState mConnectionState = ServiceConnectionState.DISCONNECTED;

    private final int  ATAK_FORWARDER = 257;

    /**
     * Service Intent
     */
    public static final String PACKAGE_NAME = "com.geeksville.mesh";
    public static final String CLASS_NAME = "com.geeksville.mesh.service.MeshService";

    /**
     * Intents the Meshtastic service can send
     */
    public static final String ACTION_MESH_CONNECTED = "com.geeksville.mesh.MESH_CONNECTED";
    public static final String ACTION_MESH_DISCONNECTED = "com.geeksville.mesh.MESH_DISCONNECTED";
    public static final String ACTION_RECEIVED_ATAK_FORWARDER = "com.geeksville.mesh.RECEIVED.ATAK_FORWARDER";
    public static final String ACTION_RECEIVED_ATAK_PLUGIN = "com.geeksville.mesh.RECEIVED.ATAK_PLUGIN";
    public static final String ACTION_RECEIVED_NODEINFO_APP = "com.geeksville.mesh.RECEIVED.NODEINFO_APP";
    public static final String ACTION_RECEIVED_POSITION_APP = "com.geeksville.mesh.RECEIVED.POSITION_APP";
    public static final String ACTION_NODE_CHANGE = "com.geeksville.mesh.NODE_CHANGE";
    public static final String ACTION_MESSAGE_STATUS = "com.geeksville.mesh.MESSAGE_STATUS";

    /**
     * Extra data fields from the Meshtastic service
     */
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

    public static MeshtasticWidget mw;
    private MeshtasticReceiver mr;
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

    @Override
    public void processCotEvent(CotEvent cotEvent, String[] strings) {
        
        if (mConnectionState == ServiceConnectionState.DISCONNECTED)
            return;

        DataPacket dp;

        Log.d(TAG, cotEvent.toString());
        CotDetail cotDetail = cotEvent.getDetail();
        Log.d(TAG, cotDetail.toString());

        // is this our self marker outgoing CoT, if so do tak_payload portnum
        if (cotEvent.getUID().equals(getMapView().getSelfMarker().getUID())) {
            Log.d(TAG, "self marker PLI");
            
            int battery = 0, course = 0, speed = 0;
            String role = null, name = null;
            String callsign = getMapView().getDeviceCallsign();
            double alt = getMapView().getSelfMarker().getPoint().getAltitude();
            double lat = getMapView().getSelfMarker().getPoint().getLatitude();
            double lng = getMapView().getSelfMarker().getPoint().getLongitude();

            Log.d(TAG, String.format("GPS: %f %f %f", alt, lat ,lng));

            try {
                XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
                factory.setNamespaceAware(true);
                XmlPullParser xpp = factory.newPullParser();
                xpp.setInput( new StringReader(cotDetail.toString()));
                int eventType = xpp.getEventType();

                while (eventType != XmlPullParser.END_DOCUMENT) {
                    if(eventType == XmlPullParser.START_TAG) {
                        if (xpp.getName().equalsIgnoreCase("contact")) {
                            int attributeCount = xpp.getAttributeCount();
                            Log.d(TAG, "Contact has " + + attributeCount);
                            for (int i=0; i< attributeCount; i++) {
                                Log.d(TAG, xpp.getAttributeName(i) + "=" + xpp.getAttributeValue(i));
                                if (xpp.getAttributeName(i).equalsIgnoreCase("callsign"))
                                    callsign = xpp.getAttributeValue(i);
                            }
                        } else if (xpp.getName().equalsIgnoreCase("__group")) {
                            int attributeCount = xpp.getAttributeCount();
                            Log.d(TAG, "__group has " + + attributeCount);
                            for (int i=0; i< attributeCount; i++) {
                                Log.d(TAG, xpp.getAttributeName(i) + "=" + xpp.getAttributeValue(i));
                                if (xpp.getAttributeName(i).equalsIgnoreCase("role"))
                                    role = xpp.getAttributeValue(i);
                                else if (xpp.getAttributeName(i).equalsIgnoreCase("name"))
                                    name = xpp.getAttributeValue(i);
                            }
                        } else if (xpp.getName().equalsIgnoreCase("status")) {
                            int attributeCount = xpp.getAttributeCount();
                            Log.d(TAG, "status has " + + attributeCount);
                            for (int i=0; i< attributeCount; i++) {
                                Log.d(TAG, xpp.getAttributeName(i) + "=" + xpp.getAttributeValue(i));
                                if (xpp.getAttributeName(i).equalsIgnoreCase("battery"))
                                    battery = Integer.parseInt(xpp.getAttributeValue(i));
                            }
                        } else if (xpp.getName().equalsIgnoreCase("track")) {
                            int attributeCount = xpp.getAttributeCount();
                            Log.d(TAG, "track has " + + attributeCount);
                            for (int i=0; i< attributeCount; i++) {
                                Log.d(TAG, xpp.getAttributeName(i) + "=" + xpp.getAttributeValue(i));
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
            }

            // represent 1e-7 as a double

            double divisor = 1e-7;

            ATAKProtos.Contact.Builder contact = ATAKProtos.Contact.newBuilder();
            contact.setCallsign(callsign);

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

            dp = new DataPacket("^all", tak_packet.build().toByteArray(), Portnums.PortNum.ATAK_PLUGIN_VALUE, DataPacket.ID_LOCAL, System.currentTimeMillis(), 0, MessageStatus.UNKNOWN, 3, 0);
            try {
                mMeshService.send(dp);
            } catch (RemoteException e) {
                throw new RuntimeException(e);
            }

        } else {
            byte[] cotAsBytes = cotShrinker.toByteArrayLossy(cotEvent);

            Log.d(TAG, "Size: " + cotAsBytes.length);

            if (cotAsBytes.length < 236) {
                Log.d(TAG, "Small send");
                dp = new DataPacket("^all", cotAsBytes, ATAK_FORWARDER, DataPacket.ID_LOCAL, System.currentTimeMillis(), 0, MessageStatus.UNKNOWN, 3, 0);
                try {
                    mMeshService.send(dp);
                } catch (RemoteException e) {
                    throw new RuntimeException(e);
                }
                return;
            }

            List<byte[]> chunkList = divideArray(cotAsBytes, 200);

            int chunks = (int) Math.floor(cotAsBytes.length / 200);
            chunks++;
            Log.d(TAG, "Sending " + chunks);

            byte[] chunk_hdr = String.format(Locale.US, "CHUNK_%d_", cotAsBytes.length).getBytes();

            for (byte[] c : chunkList) {
                byte[] combined = new byte[chunk_hdr.length + c.length];
                try {
                    System.arraycopy(chunk_hdr, 0, combined, 0, chunk_hdr.length);
                    System.arraycopy(c, 0, combined, chunk_hdr.length, c.length);
                } catch (Exception e) {
                    e.printStackTrace();
                }
                // send out 1 chunk
                dp = new DataPacket("^all", combined, ATAK_FORWARDER, DataPacket.ID_LOCAL, System.currentTimeMillis(), 0, MessageStatus.UNKNOWN, 3, 0);
                try {
                    mMeshService.send(dp);
                } catch (RemoteException e) {
                    throw new RuntimeException(e);
                }
            }

            // We're done chunking
            dp = new DataPacket("^all", new byte[]{'E', 'N', 'D'}, ATAK_FORWARDER, DataPacket.ID_LOCAL, System.currentTimeMillis(), 0, MessageStatus.UNKNOWN, 3, 0);
            try {
                mMeshService.send(dp);
            } catch (RemoteException e) {
                throw new RuntimeException(e);
            }
        }
    }


    public void onCreate(final Context context, Intent intent, MapView view) {

        CommsMapComponent.getInstance().registerPreSendProcessor(this);

        context.setTheme(R.style.ATAKPluginTheme);
        pluginContext = context;

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

        view.getContext().registerReceiver(mr, intentFilter);

        mServiceIntent = new Intent();
        mServiceIntent.setClassName(PACKAGE_NAME, CLASS_NAME);

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
            Toast.makeText(MapView.getMapView().getContext(), "Failed to bind to Meshtastic IMeshService", Toast.LENGTH_LONG).show();
        }

        mw = new MeshtasticWidget(context, view);
/*
        // Grab all the logcat output for ATAK to help debug
        try {
            String filePath = Environment.getExternalStorageDirectory() + "/atak/logcat.txt";
            Runtime.getRuntime().exec(new String[]{"logcat", "-f", filePath});
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
*/
    }

    public static boolean reconnect() throws RemoteException {
        boolean ret = MapView.getMapView().getContext().bindService(mServiceIntent, mServiceConnection, Context.BIND_AUTO_CREATE);
        if (!ret) {
            Toast.makeText(MapView.getMapView().getContext(), "Failed to bind to Meshtastic IMeshService", Toast.LENGTH_LONG).show();
        }
        return ret;
    }


    @Override
    protected void onDestroyImpl(Context context, MapView view) {
        view.getContext().unbindService(mServiceConnection);
        view.getContext().unregisterReceiver(mr);
        mw.destroy();
    }
}
