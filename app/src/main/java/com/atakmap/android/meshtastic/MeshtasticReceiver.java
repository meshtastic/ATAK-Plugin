package com.atakmap.android.meshtastic;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Xml;

import com.atakmap.android.cot.CotMapComponent;
import com.atakmap.coremap.cot.event.CotDetail;
import com.atakmap.coremap.cot.event.CotEvent;
import com.atakmap.coremap.cot.event.CotPoint;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.time.CoordinatedTime;

import com.geeksville.mesh.ATAKProtos;
import com.geeksville.mesh.DataPacket;

import com.geeksville.mesh.MessageStatus;
import com.geeksville.mesh.NodeInfo;
import com.geeksville.mesh.Portnums;
import com.google.protobuf.InvalidProtocolBufferException;


import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MeshtasticReceiver extends BroadcastReceiver {

    private final String TAG = "MeshtasticReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        Log.d(TAG, "ACTION: " + action);
        if (action.equals(MeshtasticMapComponent.ACTION_MESH_CONNECTED)) {
            String extraConnected = intent.getStringExtra(MeshtasticMapComponent.EXTRA_CONNECTED);
            boolean connected = extraConnected.equals(MeshtasticMapComponent.STATE_CONNECTED);
            Log.d(TAG, "Received ACTION_MESH_CONNECTED: " + extraConnected);
            if (connected) {
                try {
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
        }
        else if (action.equals(MeshtasticMapComponent.ACTION_MESH_DISCONNECTED)) {
            String extraConnected = intent.getStringExtra(MeshtasticMapComponent.EXTRA_DISCONNECTED);
            boolean connected = extraConnected.equals(MeshtasticMapComponent.STATE_DISCONNECTED);
            Log.d(TAG, "Received ACTION_MESH_DISCONNECTED: " + extraConnected);
            if (connected) {
                MeshtasticMapComponent.mConnectionState = MeshtasticMapComponent.ServiceConnectionState.DISCONNECTED;
                MeshtasticMapComponent.mw.setIcon("red");
            }
        }
        else if (action.equals(MeshtasticMapComponent.ACTION_MESSAGE_STATUS)) {
            int id = intent.getIntExtra(MeshtasticMapComponent.EXTRA_PACKET_ID, 0);
            MessageStatus status = intent.getParcelableExtra(MeshtasticMapComponent.EXTRA_STATUS);
            Log.d(TAG, "Message Status ID: " + id + " Status: " + status.toString());
        }
        else if (action.equals(MeshtasticMapComponent.ACTION_RECEIVED_ATAK_FORWARDER)) {
            Thread thread = new Thread(() -> receive(intent));
            thread.setName("MeshtasticReceiver.Worker");
            thread.start();
        }
        else if (action.equals("com.geeksville.mesh.RECEIVED.72")) {
            Thread thread = new Thread(() -> receive(intent));
            thread.setName("MeshtasticReceiver.Worker");
            thread.start();
        }
        else if (action.equals(MeshtasticMapComponent.ACTION_NODE_CHANGE)) {
            NodeInfo ni = intent.getParcelableExtra("com.geeksville.mesh.NodeInfo");
            Log.d(TAG, ni.toString());
        }
    }

    List<byte[]> chunks = new ArrayList<>();
    boolean chunking = false;
    int cotSize = 0;
    protected void receive(Intent intent) {
        DataPacket payload = intent.getParcelableExtra(MeshtasticMapComponent.EXTRA_PAYLOAD);
        int dataType = payload.getDataType();
        Log.v(TAG, "handleReceive(), dataType: " + dataType);

        if (dataType == Portnums.PortNum.ATAK_FORWARDER_VALUE) {
            String message = new String(payload.getBytes());
            if (message.startsWith("CHUNK")) {
                Log.d(TAG, "Received Chunked message");
                chunking = true;
                if (cotSize == 0) {
                    cotSize = Integer.parseInt(message.split("_")[1]);
                    Log.d(TAG, "Chunk CoT size: " + cotSize);
                }
                int chunk_hdr_size = String.format(Locale.US, "CHUNK_%d_", cotSize).getBytes().length;
                byte[] chunk = new byte[payload.getBytes().length - chunk_hdr_size];
                try {
                    System.arraycopy(payload.getBytes(), chunk_hdr_size, chunk, 0, payload.getBytes().length - chunk_hdr_size);
                } catch (Exception e) {
                    e.printStackTrace();
                    Log.d(TAG, "Failed to copy first chunk");
                }
                chunks.add(chunk);

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

                try {
                    CotEvent cotEvent = MeshtasticMapComponent.cotShrinker.toCotEvent(combined);
                    if (cotEvent != null && cotEvent.isValid()) {
                        Log.d(TAG, "Chunked CoT Received");

                        cotEvent.setStale(new CoordinatedTime().addDays(3));
                        CotMapComponent.getInternalDispatcher().dispatch(cotEvent);

                    } else {
                        Log.d(TAG, "Failed to libcotshrink: " + new String(combined));
                    }
                } catch (Throwable e) {
                    e.printStackTrace();
                }

            } else {
                try {

                    CotEvent cotEvent = MeshtasticMapComponent.cotShrinker.toCotEvent(payload.getBytes());
                    if (cotEvent.isValid()) {
                        Log.d(TAG, "CoT Received");
                        CotMapComponent.getInternalDispatcher().dispatch(cotEvent);
                    }
                } catch (Throwable e) {
                    e.printStackTrace();
                }

            }
        } else if (dataType == 72) {
            Log.d(TAG, "Got TAK_PACKET");
            try {

                ATAKProtos.TAKPacket tp = ATAKProtos.TAKPacket.parseFrom(payload.getBytes());
                ATAKProtos.Contact contact = tp.getContact();
                ATAKProtos.Group group = tp.getGroup();
                ATAKProtos.Status status = tp.getStatus();
                ATAKProtos.PLI pli = tp.getPli();

                double lat = pli.getLatitudeI() * 1e-7;
                double lng = pli.getLongitudeI() * 1e-7;
                double alt = pli.getAltitude();
                int course = pli.getCourse();
                int speed = pli.getSpeed();

                Log.d(TAG, String.format("GPS: %f %f %f", alt, lat ,lng));

                String callsign = contact.getCallsign();

                CotDetail cotDetail = new CotDetail("detail");

                CotDetail contactDetail = new CotDetail("contact");
                contactDetail.setAttribute("callsign", callsign);
                //contactDetail.setAttribute("phone", "0");
                //contactDetail.setAttribute("endpoint", "0.0.0.0:4242:tcp");
                cotDetail.addChild(contactDetail);

                CotDetail groupDetail = new CotDetail("__group");
                groupDetail.setAttribute("role", ATAKProtos.MemberRole.forNumber(group.getRoleValue()).name());
                groupDetail.setAttribute("name", ATAKProtos.Team.forNumber(group.getTeamValue()).name());
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
                cotEvent.setUID(callsign);

                CoordinatedTime time = new CoordinatedTime();
                cotEvent.setTime(time);
                cotEvent.setStart(time);
                cotEvent.setStale(time.addMinutes(10));

                cotEvent.setType("a-f-G-U-C");

                cotEvent.setHow("m-e");

                CotPoint cotPoint = new CotPoint(lat, lng, CotPoint.UNKNOWN,
                        CotPoint.UNKNOWN, CotPoint.UNKNOWN);
                cotEvent.setPoint(cotPoint);

                if (cotEvent.isValid())
                    CotMapComponent.getInternalDispatcher().dispatch(cotEvent);
                else
                    Log.e(TAG, "cotEvent was not valid");

            } catch (InvalidProtocolBufferException e) {
                e.printStackTrace();
            }


        }
    }
}
