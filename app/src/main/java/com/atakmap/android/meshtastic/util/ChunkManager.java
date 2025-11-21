package com.atakmap.android.meshtastic.util;

import android.content.SharedPreferences;
import com.atakmap.android.meshtastic.MeshtasticReceiver;
import com.atakmap.coremap.log.Log;
import org.meshtastic.core.model.DataPacket;
import org.meshtastic.core.service.IMeshService;
import org.meshtastic.core.model.MessageStatus;
import org.meshtastic.proto.Portnums;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.TimeoutException;

public class ChunkManager {
    private static final String TAG = "ChunkManager";
    private static final int MAX_TOTAL_SIZE = 56 * 1024; // 56KB max
    private static final int MAX_CHUNKS = 286; // Maximum number of chunks
    private static final Random random = new Random();
    private final int chunkSize;
    private final HashMap<Integer, byte[]> receivedChunks;
    private boolean isReceivingChunks;
    private int expectedChunkCount;
    private int receivedChunkCount;
    
    public ChunkManager() {
        this(Constants.DEFAULT_CHUNK_SIZE);
    }
    
    public ChunkManager(int chunkSize) {
        this.chunkSize = chunkSize;
        this.receivedChunks = new HashMap<>();
        this.isReceivingChunks = false;
        this.expectedChunkCount = 0;
        this.receivedChunkCount = 0;
    }
    
    public List<byte[]> divideIntoChunks(byte[] data) {
        List<byte[]> chunks = new ArrayList<>();
        int start = 0;
        
        while (start < data.length) {
            int end = Math.min(data.length, start + chunkSize);
            try {
                chunks.add(Arrays.copyOfRange(data, start, end));
            } catch (Exception e) {
                Log.e(TAG, "Failed to create chunk", e);
                return new ArrayList<>();
            }
            start += chunkSize;
        }
        
        return chunks;
    }
    
    public byte[] createChunkHeader(int messageId, int chunkNum, int totalChunks, int totalSize) {
        return String.format(Locale.US, Constants.CHUNK_HEADER_FORMAT, 
            messageId, chunkNum, totalChunks, totalSize).getBytes();
    }
    
    public byte[] combineHeaderAndChunk(byte[] header, byte[] chunk) {
        byte[] combined = new byte[header.length + chunk.length];
        System.arraycopy(header, 0, combined, 0, header.length);
        System.arraycopy(chunk, 0, combined, header.length, chunk.length);
        return combined;
    }
    
    public boolean sendChunkedData(byte[] data, IMeshService meshService, SharedPreferences prefs, 
                                  int hopLimit, int channel) throws Exception {
        if (meshService == null) {
            Log.e(TAG, "Mesh service is null");
            return false;
        }
        
        if (data == null || data.length == 0) {
            Log.e(TAG, "Invalid data to send");
            return false;
        }
        
        if (data.length > MAX_TOTAL_SIZE) {
            Log.e(TAG, "Data too large to send: " + data.length + " bytes (max: " + MAX_TOTAL_SIZE + ")");
            return false;
        }
        
        List<byte[]> chunks = divideIntoChunks(data);
        if (chunks.isEmpty()) {
            return false;
        }
        
        if (chunks.size() > MAX_CHUNKS) {
            Log.e(TAG, "Too many chunks: " + chunks.size() + " (max: " + MAX_CHUNKS + ")");
            return false;
        }
        
        // Generate unique message ID
        int messageId = random.nextInt(Integer.MAX_VALUE);
        Log.d(TAG, "Sending chunked message " + messageId + " (" + chunks.size() + " chunks, " + data.length + " bytes)");
        
        // Send each chunk with proper numbering
        for (int i = 0; i < chunks.size(); i++) {
            byte[] header = createChunkHeader(messageId, i, chunks.size(), data.length);
            byte[] combined = combineHeaderAndChunk(header, chunks.get(i));
            
            DataPacket dp = new DataPacket(
                DataPacket.ID_BROADCAST,
                combined,
                Portnums.PortNum.ATAK_FORWARDER_VALUE,
                DataPacket.ID_LOCAL,
                System.currentTimeMillis(),
                0,
                MessageStatus.UNKNOWN,
                hopLimit,
                channel,
                false, // Don't wait for ACKs - let Meshtastic handle retransmissions
                0,  // hopStart
                0f, // snr
                0,  // rssi
                null // replyId
            );
            
            meshService.send(dp);
            Log.d(TAG, "Sent chunk " + i + "/" + chunks.size() + " for message " + messageId);
            
            // Small delay between chunks to avoid overwhelming the radio
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedException("Chunk sending interrupted");
            }
        }
        
        // Send end marker with message ID
        String endMarker = "END_" + messageId + "_";
        DataPacket endPacket = new DataPacket(
            DataPacket.ID_BROADCAST,
            endMarker.getBytes(),
            Portnums.PortNum.ATAK_FORWARDER_VALUE,
            DataPacket.ID_LOCAL,
            System.currentTimeMillis(),
            0,
            MessageStatus.UNKNOWN,
            hopLimit,
            channel,
            false,
            0,  // hopStart
            0f, // snr
            0,  // rssi
            null // replyId
        );
        
        meshService.send(endPacket);
        Log.d(TAG, "Sent END marker for message " + messageId);
        return true;
    }
    

    
    public void startReceiving(int totalSize) {
        if (totalSize <= 0 || totalSize > MAX_TOTAL_SIZE) {
            Log.e(TAG, "Invalid total size for receiving: " + totalSize);
            return;
        }
        
        isReceivingChunks = true;
        expectedChunkCount = (int) Math.ceil((double) totalSize / chunkSize);
        
        if (expectedChunkCount > MAX_CHUNKS) {
            Log.e(TAG, "Too many expected chunks: " + expectedChunkCount);
            reset();
            return;
        }
        
        receivedChunkCount = 0;
        receivedChunks.clear();
    }
    
    public boolean addReceivedChunk(int index, byte[] data) {
        if (!isReceivingChunks) {
            return false;
        }
        
        receivedChunks.put(index, data);
        receivedChunkCount++;
        
        return receivedChunkCount >= expectedChunkCount;
    }
    
    public byte[] assembleChunks() {
        if (!isReceivingChunks || receivedChunkCount < expectedChunkCount) {
            return null;
        }
        
        int totalSize = 0;
        for (byte[] chunk : receivedChunks.values()) {
            totalSize += chunk.length;
        }
        
        byte[] result = new byte[totalSize];
        int position = 0;
        
        for (int i = 0; i < receivedChunks.size(); i++) {
            byte[] chunk = receivedChunks.get(i);
            if (chunk != null) {
                System.arraycopy(chunk, 0, result, position, chunk.length);
                position += chunk.length;
            }
        }
        
        reset();
        return result;
    }
    
    public void reset() {
        isReceivingChunks = false;
        expectedChunkCount = 0;
        receivedChunkCount = 0;
        receivedChunks.clear();
    }
    
    public boolean isReceiving() {
        return isReceivingChunks;
    }
}