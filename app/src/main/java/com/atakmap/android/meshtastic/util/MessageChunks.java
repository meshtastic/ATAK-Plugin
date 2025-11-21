package com.atakmap.android.meshtastic.util;

import com.atakmap.coremap.log.Log;
import java.util.Arrays;
import java.util.HashMap;

/**
 * Tracks chunks for a single message being reassembled
 */
public class MessageChunks {
    private static final String TAG = "MessageChunks";
    private final int messageId;
    private final int totalChunks;
    private final int totalSize;
    private final HashMap<Integer, byte[]> chunks;
    private final long startTime;
    
    public MessageChunks(int messageId, int totalChunks, int totalSize) {
        this.messageId = messageId;
        this.totalChunks = totalChunks;
        this.totalSize = totalSize;
        this.chunks = new HashMap<>();
        this.startTime = System.currentTimeMillis();
    }
    
    /**
     * Add a chunk to this message
     * @param chunkNum Chunk number (0-based)
     * @param data Chunk data
     * @return true if chunk was added, false if duplicate
     */
    public boolean addChunk(int chunkNum, byte[] data) {
        if (chunkNum < 0 || chunkNum >= totalChunks) {
            Log.w(TAG, "Invalid chunk number " + chunkNum + " for message " + messageId + 
                       " (expected 0-" + (totalChunks - 1) + ")");
            return false; // Invalid chunk number
        }
        
        if (chunks.containsKey(chunkNum)) {
            // Check if it's truly a duplicate (same data)
            byte[] existing = chunks.get(chunkNum);
            if (Arrays.equals(existing, data)) {
                Log.v(TAG, "Duplicate chunk " + chunkNum + " for message " + messageId + " (identical data)");
                return false; // Duplicate, ignore
            } else {
                // Different data for same chunk number - corruption?
                Log.w(TAG, "Chunk " + chunkNum + " for message " + messageId + 
                           " has different data (existing: " + existing.length + 
                           " bytes, new: " + data.length + " bytes) - keeping original");
                return false; // Keep original
            }
        }
        
        chunks.put(chunkNum, data);
        Log.v(TAG, "Added chunk " + chunkNum + " (" + data.length + " bytes) to message " + messageId);
        return true;
    }
    
    /**
     * Check if all chunks have been received
     */
    public boolean isComplete() {
        return chunks.size() == totalChunks;
    }
    
    /**
     * Check if this message has timed out
     */
    public boolean isTimedOut() {
        return System.currentTimeMillis() - startTime > Constants.CHUNK_TIMEOUT_MS;
    }
    
    /**
     * Reassemble all chunks into the original data
     * @return Reassembled data, or null if not complete
     */
    public byte[] reassemble() {
        if (!isComplete()) {
            Log.w(TAG, "Cannot reassemble message " + messageId + " - only " + 
                       chunks.size() + "/" + totalChunks + " chunks received");
            return null;
        }
        
        byte[] result = new byte[totalSize];
        int position = 0;
        
        // Reassemble in correct order
        for (int i = 0; i < totalChunks; i++) {
            byte[] chunk = chunks.get(i);
            if (chunk == null) {
                Log.e(TAG, "Missing chunk " + i + " for message " + messageId + " during reassembly");
                return null; // Missing chunk
            }
            System.arraycopy(chunk, 0, result, position, chunk.length);
            position += chunk.length;
        }
        
        Log.d(TAG, "Successfully reassembled message " + messageId + " (" + 
                   totalChunks + " chunks, " + position + " bytes)");
        return result;
    }
    
    public int getMessageId() {
        return messageId;
    }
    
    public int getTotalChunks() {
        return totalChunks;
    }
    
    public int getReceivedChunks() {
        return chunks.size();
    }
    
    public int getTotalSize() {
        return totalSize;
    }
    
    public long getStartTime() {
        return startTime;
    }
}
