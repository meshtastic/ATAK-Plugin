package com.atakmap.android.meshtastic;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.atakmap.android.cot.CotMapComponent;
import com.atakmap.comms.CommsMapComponent;
import com.atakmap.coremap.cot.event.CotEvent;
import com.atakmap.coremap.log.Log;
import com.geeksville.mesh.DataPacket;
import com.geeksville.mesh.MessageStatus;
import com.geeksville.mesh.NodeInfo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public class MeshtasticReceiver extends BroadcastReceiver {

    private final String TAG = "MeshtasticReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        Log.d(TAG, action);
        if (action.equals(MeshtasticMapComponent.ACTION_MESH_CONNECTED)) {
            String extraConnected = intent.getStringExtra(MeshtasticMapComponent.EXTRA_CONNECTED);
            boolean connected = extraConnected.equals(MeshtasticMapComponent.STATE_CONNECTED);
            Log.d(TAG, "Received ACTION_MESH_CONNECTED: " + extraConnected);
            if (connected)
                MeshtasticMapComponent.mConnectionState = MeshtasticMapComponent.ServiceConnectionState.CONNECTED;
        }
        else if (action.equals(MeshtasticMapComponent.ACTION_MESH_DISCONNECTED)) {
            String extraConnected = intent.getStringExtra(MeshtasticMapComponent.EXTRA_DISCONNECTED);
            boolean connected = extraConnected.equals(MeshtasticMapComponent.STATE_DISCONNECTED);
            Log.d(TAG, "Received ACTION_MESH_DISCONNECTED: " + extraConnected);
            if (connected)
                MeshtasticMapComponent.mConnectionState = MeshtasticMapComponent.ServiceConnectionState.DISCONNECTED;
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
        if (dataType == 257) {
            String message = new String(payload.getBytes());
            if (message.startsWith("CHUNK")) {
                Log.d(TAG, "Received Chunked message");
                chunking = true;
                if (cotSize == 0) {
                    cotSize = Integer.parseInt(message.split("_")[1]);
                    Log.d(TAG, "CoT size: " + cotSize);
                }

                int chunk_hdr_size = String.format(Locale.US, "CHUNKS_%d_", cotSize).getBytes().length - 1;
                byte[] chunk = new byte[payload.getBytes().length - chunk_hdr_size];
                Log.d(TAG, "Chunk header size: " + chunk_hdr_size);
                System.arraycopy(payload.getBytes(), chunk_hdr_size, chunk, 0, payload.getBytes().length - chunk_hdr_size);

                Log.d(TAG, "Chunk: " + new String(chunk));
                chunks.add(chunk);

            } else if (chunking) {
                Log.d(TAG, "Chunking");
                chunking = false;
                byte[] combined = new byte[cotSize];

                int i = 0;
                for (byte[] b: chunks) {
                    Log.d(TAG, new String(b));
                    Log.d(TAG, "len: " + b.length);
                    Log.d(TAG, "====");
                    System.arraycopy(b,0, combined, i, b.length);
                    i += b.length;
                    Log.d(TAG, "i: " + i);
                }

                Log.d(TAG, "Chunked size: " + i);

                CotEvent cotEvent = MeshtasticMapComponent.cotShrinker.toCotEvent(combined);
                if (cotEvent != null && cotEvent.isValid()) {
                    Log.d(TAG, "CoT Received");
                    CotMapComponent.getInternalDispatcher().dispatch(cotEvent);
                    chunks.clear();
                    chunking = false;
                } else {
                    Log.d(TAG, "Failed to libcotshrink: " + new String(combined));
                }
            }
            else {
                CotEvent cotEvent = MeshtasticMapComponent.cotShrinker.toCotEvent(payload.getBytes());
                if (cotEvent.isValid()) {
                    Log.d(TAG, "CoT Received");
                    CotMapComponent.getInternalDispatcher().dispatch(cotEvent);
                }
            }
        }
    }
}
