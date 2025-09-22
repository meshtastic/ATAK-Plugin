package com.atakmap.android.meshtastic;

import static com.atakmap.android.maps.MapView._mapView;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.preference.PreferenceManager;
import android.speech.tts.TextToSpeech;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.atakmap.android.dropdown.DropDown;
import com.atakmap.android.dropdown.DropDownReceiver;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.meshtastic.plugin.R;
import com.atakmap.android.meshtastic.util.Constants;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.filesystem.FileSystemUtils;
import com.geeksville.mesh.ATAKProtos;
import com.geeksville.mesh.AppOnlyProtos;
import com.geeksville.mesh.ConfigProtos;
import com.geeksville.mesh.DataPacket;
import com.geeksville.mesh.LocalOnlyProtos;
import com.geeksville.mesh.MeshProtos;
import com.geeksville.mesh.MessageStatus;
import com.geeksville.mesh.NodeInfo;
import com.geeksville.mesh.Portnums;
import com.google.protobuf.ByteString;
import com.ustadmobile.codec2.Codec2;

import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.RecognitionListener;
import org.vosk.android.SpeechService;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import java.nio.ShortBuffer;


public class MeshtasticDropDownReceiver extends DropDownReceiver implements
        DropDown.OnStateListener, RecognitionListener {

    private static final String TAG = "MeshtasticDropDownReceiver";
    public static final String SHOW_PLUGIN = "com.atakmap.android.meshtastic.SHOW_PLUGIN";
    private final static List<String> allowedStrings = Arrays.asList("and", "zero", "one", "two", "three", "four", "five",
            "six", "seven", "eight", "nine", "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen",
            "seventeen", "eighteen", "nineteen", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty",
            "ninety", "hundred", "thousand", "million", "billion", "trillion");
    private final Context pluginContext;
    private final Context appContext;
    private final MapView mapView;
    private final View mainView;
    private Button voiceMemoBtn, talk;
    private Model model;
    public SpeechService speechService;
    private TextView tv;
    private int toggle = 0;
    private View.OnKeyListener keyListener;
    public static TextToSpeech t1;
    private SharedPreferences prefs;
    private int hopLimit = 3;
    private int channel = 0;
    private boolean audioPermissionGranted = false;
    private static final int RECORDER_SAMPLERATE = Constants.AUDIO_SAMPLE_RATE;
    private AudioRecord recorder = null;
    private Thread recordingThread = null;
    private AtomicBoolean isRecording = new AtomicBoolean(false);
    private short recorderBuf[] = null;
    private char encodedBuf[] = null;
    private long c2 = 0;
    private int c2FrameSize = 0;
    private int samplesBufSize = 0;
    private Activity activity;


    protected MeshtasticDropDownReceiver(final MapView mapView, final Context context) {
        super(mapView);
        this.pluginContext = context;
        this.appContext = mapView.getContext();
        this.mapView = mapView;
        this.prefs = PreferenceManager.getDefaultSharedPreferences(mapView.getContext().getApplicationContext());
        this.activity = (Activity) mapView.getContext();


        LayoutInflater inflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        this.mainView = inflater.inflate(R.layout.main_layout, null);
        tv = mainView.findViewById(R.id.tv);
        voiceMemoBtn = mainView.findViewById(R.id.voiceMemoBtn);
        voiceMemoBtn.setOnClickListener(v -> {
            if ((toggle++ % 2) == 0) {
                try {
                    recognizeMicrophone();
                } catch (Exception e) {
                    e.printStackTrace();
                }
                voiceMemoBtn.setText("Recording...");
            } else {
                if (speechService != null) {
                    speechService.stop();
                    speechService = null;
                }
                voiceMemoBtn.setText("Voice Memo");
            }
        });

        // Check if user has given permission to record audio, init the model after permission is granted
        int permissionCheck = ContextCompat.checkSelfPermission(_mapView.getContext().getApplicationContext(), Manifest.permission.RECORD_AUDIO);
        if (permissionCheck != PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "REC AUDIO DENIED");
        } else {
            try {
                Log.d(TAG, "REC AUDIO GRANTED");
                initModel();

                audioPermissionGranted = true;

                // button to voice talk to all devices
                talk = mainView.findViewById(R.id.talk);
                talk.setOnClickListener(v -> {
                    if (!isRecording.getAndSet(true)) {
                        talk.setText("Stop");
                        recordVoice(true);
                    } else {
                        isRecording.set(false);
                        talk.setText("All Talk");
                        Log.d(TAG, "Recording stopped");
                        /*
                        if (codec2_chunks.size() > 0) {
                            new Thread(() -> {
                                try {
                                    Thread.sleep(500);
                                    byte[] audio = new byte[0];
                                    for (int i = 0; i < codec2_chunks.size(); i++)
                                        audio = append(audio, (byte[]) codec2_chunks.get(i));
                                    Log.d(TAG, "audio total bytes: " + audio.length);

                                    codec2_chunks.clear();

                                    // 0xC2 is my codec2 header
                                    DataPacket dp = new DataPacket(DataPacket.ID_BROADCAST, append(new byte[]{(byte) 0xC2}, audio), Portnums.PortNum.ATAK_FORWARDER_VALUE, DataPacket.ID_LOCAL, System.currentTimeMillis(), 0, MessageStatus.UNKNOWN, MeshtasticReceiver.getHopLimit(), MeshtasticReceiver.getChannelIndex(), MeshtasticReceiver.getWantsAck());
                                    MeshtasticMapComponent.sendToMesh(dp);
                                } catch (InterruptedException e) {
                                    e.printStackTrace();
                                }
                            }).start();
                        }
                        */
                    }
                });
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        t1 = new TextToSpeech(_mapView.getContext(), status -> {
            if (status != TextToSpeech.ERROR) {
                t1.setLanguage(Locale.ENGLISH);
            }
        });

        AtomicBoolean recording = new AtomicBoolean(false);
        keyListener = (v, keyCode, event) -> {
            Log.d(TAG, "keyCode: " + keyCode + " onKeyEvent: " + event.toString());
            int pttKey = 0;
            try {
                pttKey = Integer.valueOf(prefs.getString(Constants.PREF_PLUGIN_PTT, "0"));
            } catch (NumberFormatException e) {
                Log.d(TAG, "PTT key not set");
                return false;
            }
            if (keyCode == pttKey && event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() > 0 && !recording.get()) {
                // start recording
                recording.set(true);
                recognizeMicrophone();
                Log.d(TAG, "start recording");
                return true;

            } else if (keyCode == pttKey && event.getAction() == KeyEvent.ACTION_UP) {
                // stop recording
                recording.set(false);
                if (speechService != null) {
                    speechService.stop();
                    speechService = null;
                    Log.d(TAG, "stop recording");
                }
                return true;
            }
            return false;
        };
        mapView.addOnKeyListener(keyListener);

        // Codec2 Recorder/Playback - hardcoded to 700C for compatibility
        // NOTE: All devices must use the same codec mode for audio to work properly
        c2 = Codec2.create(Codec2.CODEC2_MODE_700C);
        if (c2 == 0) {
            Log.e(TAG, "Failed to create Codec2 with mode 700C");
        }
        c2FrameSize = Codec2.getBitsSize(c2);
        samplesBufSize = Codec2.getSamplesPerFrame(c2);
        recorderBuf = new short[samplesBufSize];
        Log.d(TAG, "Codec2 initialized with mode 700C, frame size: " + c2FrameSize + ", samples: " + samplesBufSize);
    }

    private final Queue<byte[]> recordingQueue = new ConcurrentLinkedQueue<>();
    private boolean isRecordingActive = false;

    public synchronized void recordVoice(boolean isBroadcast) {
        if (isRecordingActive) {
            activity.runOnUiThread(() -> {
                Toast.makeText(appContext, "Already recording, waiting for previous to finish...", Toast.LENGTH_SHORT).show();
            });
            return;
        }

        isRecordingActive = true; // Prevent multiple recordings
        
        // Clear any leftover chunks from previous recording
        synchronized (recordingQueue) {
            if (!recordingQueue.isEmpty()) {
                Log.w(TAG, "Clearing " + recordingQueue.size() + " leftover chunks from previous recording");
                recordingQueue.clear();
            }
        }

        new Thread(() -> {
            try {
                startRecording();
                processAudio(isBroadcast);
            } catch (Exception e) {
                Log.e(TAG, "Error during recording", e);
            } finally {
                // Ensure any remaining audio is sent
                if (!recordingQueue.isEmpty()) {
                    Log.d(TAG, "Sending remaining audio in finally block");
                    sendAudio(isBroadcast);
                }
                isRecordingActive = false;
                stopRecording(); // Ensure recorder is always cleaned up
            }
        }).start();
    }

    private void startRecording() {
        try {
            if (recorder != null) {
                recorder.stop();
                recorder.release();
                recorder = null;
            }

            int minAudioBufSize = AudioRecord.getMinBufferSize(
                    RECORDER_SAMPLERATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
            );

            if (ActivityCompat.checkSelfPermission(_mapView.getContext(), Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "Record Audio Permission denied");
                return;
            }

            recorder = new AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    RECORDER_SAMPLERATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(minAudioBufSize, samplesBufSize * 2)
            );

            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize.");
                return;
            }

            recorder.startRecording();
            Log.d(TAG, "Recording started...");
        } catch (Exception e) {
            Log.e(TAG, "Error initializing AudioRecord: " + e.getMessage());
        }
    }

    private void processAudio(boolean isBroadcast) {
        byte[] frame;
        char[] encodedBuf = new char[c2FrameSize];
        
        // Configurable thresholds for better performance
        final int MIN_CHUNK_THRESHOLD = 4; // Minimum chunks before sending (reduces overhead)
        final int MAX_CHUNK_THRESHOLD = 12; // Maximum chunks to prevent large packets
        final long MAX_WAIT_TIME_MS = 500; // Max time to wait before sending (500ms)
        
        long lastSendTime = System.currentTimeMillis();

        while (isRecording.get()) {
            int readBytes = recorder.read(recorderBuf, 0, recorderBuf.length);
            if (readBytes > 0) {
                Codec2.encode(c2, recorderBuf, encodedBuf);
                frame = charArrayToByteArray(encodedBuf);

                if (frame.length == 0) continue; // Prevent empty frames

                synchronized (recordingQueue) {
                    recordingQueue.add(frame);
                }
            }

            // Intelligent sending logic:
            // 1. Send immediately if we hit max threshold (prevent packet too large)
            // 2. Send if we have min threshold AND enough time has passed
            // 3. Send if max wait time exceeded (prevent audio delay)
            long currentTime = System.currentTimeMillis();
            long timeSinceLastSend = currentTime - lastSendTime;
            int queueSize = recordingQueue.size();
            
            boolean shouldSend = false;
            
            if (queueSize >= MAX_CHUNK_THRESHOLD) {
                // Prevent packets from getting too large
                shouldSend = true;
                Log.d(TAG, "Sending audio: max threshold reached (" + queueSize + " chunks)");
            } else if (queueSize >= MIN_CHUNK_THRESHOLD && timeSinceLastSend >= MAX_WAIT_TIME_MS) {
                // Balance between latency and efficiency
                shouldSend = true;
                Log.d(TAG, "Sending audio: min threshold with timeout (" + queueSize + " chunks, " + timeSinceLastSend + "ms)");
            }
            
            if (shouldSend) {
                sendAudio(isBroadcast);
                lastSendTime = currentTime;
            }
        }

        // CRITICAL: Always flush remaining audio when recording stops
        // This ensures no audio chunks are lost, regardless of threshold
        if (!recordingQueue.isEmpty()) {
            Log.d(TAG, "Recording stopped - flushing remaining " + recordingQueue.size() + " audio chunks");
            sendAudio(isBroadcast);
        }
        
        stopRecording();
    }

    private void sendAudio(boolean isBroadcast) {
        byte[] audio;
        synchronized (recordingQueue) {
            if (recordingQueue.isEmpty()) {
                Log.d(TAG, "No audio chunks to send");
                return;
            }

            // Calculate total size safely
            int totalSize = 0;
            for (byte[] chunk : recordingQueue) {
                totalSize += chunk.length;
            }
            
            // Validate size before allocation
            if (totalSize <= 0 || totalSize > 1024 * 1024) { // Max 1MB for safety
                Log.e(TAG, "Invalid audio size: " + totalSize);
                recordingQueue.clear();
                return;
            }

            audio = new byte[totalSize];
            int offset = 0;

            for (byte[] chunk : recordingQueue) {
                System.arraycopy(chunk, 0, audio, offset, chunk.length);
                offset += chunk.length;
            }

            recordingQueue.clear();
        }

        Log.d(TAG, "Sending audio packet: " + audio.length + " bytes");

        if (isBroadcast) {
            try {
                // Add codec2 header (0xC2) to indicate this is codec2 audio
                byte[] packetData = append(new byte[]{(byte) 0xC2}, audio);
                
                DataPacket dp = new DataPacket(
                        DataPacket.ID_BROADCAST,
                        packetData,
                        Portnums.PortNum.ATAK_FORWARDER_VALUE,
                        DataPacket.ID_LOCAL,
                        System.currentTimeMillis(),
                        0,
                        MessageStatus.UNKNOWN,
                        0, // no hops for audio to reduce latency
                        MeshtasticReceiver.getChannelIndex(),
                        MeshtasticReceiver.getWantsAck()
                );
                
                MeshtasticMapComponent.sendToMesh(dp);
                Log.d(TAG, "Audio packet sent successfully");
            } catch (Exception e) {
                Log.e(TAG, "Failed to send audio packet", e);
            }
        }
    }

    private void stopRecording() {
        if (recorder != null) {
            try {
                if (recorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    recorder.stop();
                }
            } catch (Exception e) {
                Log.e(TAG, "Error stopping recorder", e);
            } finally {
                try {
                    recorder.release();
                } catch (Exception e) {
                    Log.e(TAG, "Error releasing recorder", e);
                }
                recorder = null;
            }
        }
        Log.d(TAG, "Recording stopped and resources released");
    }

    // convert char array to byte array
    private static byte[] charArrayToByteArray(char[] c_array) {
        byte[] b_array = new byte[c_array.length];
        for(int i= 0; i < c_array.length; i++) {
            b_array[i] = (byte)(0xFF & c_array[i]);
        }
        return b_array;
    }

    public boolean endsWith(byte[] needle, byte[] haystack) {
        if (needle.length > haystack.length)
            return false;
        for (int i = 0; i < needle.length; i++) {
            if (needle[needle.length - i - 1] != haystack[haystack.length - i - 1])
                return false;
        }
        return true;
    }

    // byte array slice method
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

    // byte array append
    public byte[] append(byte[] a, byte[] b) {
        byte[] result = new byte[a.length + b.length];
        for (int i = 0; i < a.length; i++)
            result[i] = a[i];
        for (int i = 0; i < b.length; i++)
            result[a.length + i] = b[i];
        return result;
    }

    // short array append
    public short[] append(short[] a, short[] b) {
        short[] result = new short[a.length + b.length];
        for (int i = 0; i < a.length; i++)
            result[i] = a[i];
        for (int i = 0; i < b.length; i++)
            result[a.length + i] = b[i];
        return result;
    }
    private static final char[] HEX_ARRAY = "0123456789ABCDEF".toCharArray();
    public static String bytesToHex(byte[] bytes) {
        char[] hexChars = new char[bytes.length * 2];
        for (int j = 0; j < bytes.length; j++) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = HEX_ARRAY[v >>> 4];
            hexChars[j * 2 + 1] = HEX_ARRAY[v & 0x0F];
        }
        return new String(hexChars);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();

        if (action != null && action.equals(SHOW_PLUGIN)) {
            showDropDown(mainView, HALF_WIDTH, FULL_HEIGHT,
                    FULL_WIDTH, HALF_HEIGHT, false, this);
        }
    }
    public void initModel() throws IOException {

        File f = new File("/sdcard/atak/tools/s2c/model");
        if (!f.exists()) {
            f.mkdirs();
        }

        AssetManager assetManager = pluginContext.getAssets();
        File externalFilesDir = new File("/sdcard/atak/tools/s2c");
        String sourcePath = "model-en-us";
        String targetPath = "model";

        File targetDir = new File(externalFilesDir, targetPath);
        String resultPath = new File(targetDir, sourcePath).getAbsolutePath();
        String sourceUUID = readLine(assetManager.open(sourcePath + "/uuid"));

        deleteContents(targetDir);
        copyAssets(assetManager, sourcePath, targetDir);
        // Copy uuid
        FileSystemUtils.copyFromAssetsToStorageFile(
            pluginContext,
            sourcePath + "/uuid",
            "tools/s2c/" + targetPath + "/" + sourcePath + "/uuid",
            true
        );

        this.model = new Model(resultPath);
        Log.d(TAG, "Model ready");

    }

    private static String readLine(InputStream is) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
            return reader.readLine();
        }
    }

    private static boolean deleteContents(File dir) {
        File[] files = dir.listFiles();
        boolean success = true;
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    success &= deleteContents(file);
                }
                if (!file.delete()) {
                    success = false;
                }
            }
        }
        return success;
    }

    private static void copyAssets(AssetManager assetManager, String path, File outPath) throws IOException {
        String[] assets = assetManager.list(path);
        if (assets == null) {
            return;
        }
        if (assets.length == 0) {
            if (!path.endsWith("uuid"))
                copyFile(assetManager, path, outPath);
        } else {
            File dir = new File(outPath, path);
            if (!dir.exists()) {
                if (!dir.mkdirs()) {
                    Log.v(TAG, "Failed to create directory " + dir.getAbsolutePath());
                }
            }
            for (String asset : assets) {
                copyAssets(assetManager, path + "/" + asset, outPath);
            }
        }
    }


    public void recognizeMicrophone() {
        Log.d(TAG, "recognizeMicrophone");
        if (speechService != null) {
            speechService.stop();
            speechService = null;
            Log.d(TAG, "speechSerivce: STOPPED");
        } else {
            try {
                Recognizer rec = new Recognizer(model, 16000.0f);
                speechService = new SpeechService(rec, 16000.0f);
                speechService.startListening(this);
                Log.d(TAG, "SPEECH RECORDING");
            } catch (Exception e) {
                Log.d(TAG, "ERROR");
                e.printStackTrace();
            }
        }
    }

    private void pause(boolean checked) {
        if (speechService != null) {
            speechService.setPause(checked);
        }
    }
    @Override
    public void onDropDownSelectionRemoved() {
    }

    @Override
    public void onDropDownVisible(boolean v) {
    }

    @Override
    public void onDropDownSizeChanged(double width, double height) {
    }

    @Override
    public void onDropDownClose() {
    }

    /**
     * Reinitialize codec with new settings
     */
    public void reinitializeCodec() {
        // Stop recording if active
        stopRecording();
        
        // Destroy old codec instance if it exists
        if (c2 != 0) {
            try {
                Codec2.destroy(c2);
            } catch (Exception e) {
                Log.e(TAG, "Error destroying old Codec2 instance", e);
            }
        }
        
        // Create new codec - hardcoded to 700C for compatibility
        c2 = Codec2.create(Codec2.CODEC2_MODE_700C);
        if (c2 == 0) {
            Log.e(TAG, "Failed to create Codec2 with mode 700C");
        }
        c2FrameSize = Codec2.getBitsSize(c2);
        samplesBufSize = Codec2.getSamplesPerFrame(c2);
        recorderBuf = new short[samplesBufSize];
        Log.d(TAG, "Codec2 reinitialized with mode 700C, frame size: " + c2FrameSize + ", samples: " + samplesBufSize);
    }
    
    @Override
    protected void disposeImpl() {
        mapView.removeOnKeyListener(keyListener);
        
        // Clean up Codec2 resources
        if (c2 != 0) {
            try {
                Codec2.destroy(c2);
                c2 = 0;
            } catch (Exception e) {
                Log.e(TAG, "Error destroying Codec2 instance", e);
            }
        }
        
        // Clean up audio recorder if it exists
        stopRecording();
    }

    @Override
    public void onPartialResult(String hypothesis) {
    }

    @Override
    public void onResult(String hypothesis) {

    }

    @Override
    public void onFinalResult(String hypothesis) {
        Log.d(TAG, "Final: "  + hypothesis);
        String converted = convertTextualNumbersInDocument(hypothesis).split(":")[1].split("\n")[0].replace("\"","");
        Log.d(TAG, converted);
        tv.setText("Sending: " + converted);
        //t1.speak(converted, TextToSpeech.QUEUE_FLUSH, null);

        ATAKProtos.Contact.Builder contact = ATAKProtos.Contact.newBuilder();
        contact.setCallsign(mapView.getDeviceCallsign());
        contact.setDeviceCallsign(mapView.getSelfMarker().getUID());

        ATAKProtos.GeoChat.Builder geochat = ATAKProtos.GeoChat.newBuilder();
        geochat.setMessage(converted);
        geochat.setTo("All Chat Rooms");

        ATAKProtos.TAKPacket.Builder tak_packet = ATAKProtos.TAKPacket.newBuilder();
        tak_packet.setContact(contact);
        tak_packet.setChat(geochat);

        Log.d(TAG, "Total wire size for TAKPacket: " + tak_packet.build().toByteArray().length);
        Log.d(TAG, "Sending: " + tak_packet.build().toString());

        ByteString payload = ByteString.copyFrom(converted.getBytes());

        hopLimit = MeshtasticReceiver.getHopLimit();
        channel = MeshtasticReceiver.getChannelIndex();

        DataPacket dp = new DataPacket(DataPacket.ID_BROADCAST, MeshProtos.Data.newBuilder().setPayload(payload).build().toByteArray(),Portnums.PortNum.TEXT_MESSAGE_APP_VALUE, DataPacket.ID_LOCAL, System.currentTimeMillis(), 0, MessageStatus.UNKNOWN, hopLimit, channel, MeshtasticReceiver.getWantsAck());
        MeshtasticMapComponent.sendToMesh(dp);
    }

    public static String convertTextualNumbersInDocument(String inputText) {

        // splits text into words and deals with hyphenated numbers. Use linked
        // list due to manipulation during processing
        List<String> words = new LinkedList<String>(cleanAndTokenizeText(inputText));

        // replace all the textual numbers
        words = replaceTextualNumbers(words);

        // put spaces back in and return the string. Should be the same as input
        // text except from textual numbers
        return wordListToString(words);
    }

    private static List<String> replaceTextualNumbers(List<String> words) {

        // holds each group of textual numbers being processed together. e.g.
        // "one" or "five hundred and two"
        List<String> processingList = new LinkedList<String>();

        int i = 0;
        while (i < words.size() || !processingList.isEmpty()) {

            // caters for sentences only containing one word (that is a number)
            String word = "";
            if (i < words.size()) {
                word = words.get(i);
            }

            // strip word of all punctuation to make it easier to process
            String wordStripped = word.replaceAll("[^a-zA-Z\\s]", "").toLowerCase();

            // 2nd condition: skip "and" words by themselves and at start of num
            if (allowedStrings.contains(wordStripped) && !(processingList.size() == 0 && wordStripped.equals("and"))) {
                words.remove(i); // remove from main list, will process later
                processingList.add(word);
            } else if (processingList.size() > 0) {
                // found end of group of textual words to process

                //if "and" is the last word, add it back in to original list
                String lastProcessedWord = processingList.get(processingList.size() - 1);
                if (lastProcessedWord.equals("and")) {
                    words.add(i, "and");
                    processingList.remove(processingList.size() - 1);
                }

                // main logic here, does the actual conversion
                String wordAsDigits = String.valueOf(convertWordsToNum(processingList));

                wordAsDigits = retainPunctuation(processingList, wordAsDigits);
                words.add(i, String.valueOf(wordAsDigits));

                processingList.clear();
                i += 2;
            } else {
                i++;
            }
        }

        return words;
    }

    private static String retainPunctuation(List<String> processingList, String wordAsDigits) {

        String lastWord = processingList.get(processingList.size() - 1);
        char lastChar = lastWord.trim().charAt(lastWord.length() - 1);
        if (!Character.isLetter(lastChar)) {
            wordAsDigits += lastChar;
        }

        String firstWord = processingList.get(0);
        char firstChar = firstWord.trim().charAt(0);
        if (!Character.isLetter(firstChar)) {
            wordAsDigits = firstChar + wordAsDigits;
        }

        return wordAsDigits;
    }

    private static List<String> cleanAndTokenizeText(String sentence) {
        List<String> words = new LinkedList<String>(Arrays.asList(sentence.split(" ")));

        // remove hyphenated textual numbers
        for (int i = 0; i < words.size(); i++) {
            String str = words.get(i);
            if (str.contains("-")) {
                List<String> splitWords = Arrays.asList(str.split("-"));

                // just check the first word is a textual number. Caters for
                // "twenty-five," without having to strip the comma
                if (splitWords.size() > 1 && allowedStrings.contains(splitWords.get(0))) {
                    words.remove(i);
                    words.addAll(i, splitWords);
                }
            }

        }

        return words;
    }

    private static String wordListToString(List<String> list) {
        StringBuilder result = new StringBuilder("");
        for (int i = 0; i < list.size(); i++) {
            String str = list.get(i);
            if (i == 0 && str != null) {
                result.append(list.get(i));
            } else if (str != null) {
                result.append(" " + list.get(i));
            }
        }

        return result.toString();
    }

    private static long convertWordsToNum(List<String> words) {
        long finalResult = 0;
        long intermediateResult = 0;
        for (String str : words) {
            // clean up string for easier processing
            str = str.toLowerCase().replaceAll("[^a-zA-Z\\s]", "");
            if (str.equalsIgnoreCase("zero")) {
                intermediateResult += 0;
            } else if (str.equalsIgnoreCase("one")) {
                intermediateResult += 1;
            } else if (str.equalsIgnoreCase("two")) {
                intermediateResult += 2;
            } else if (str.equalsIgnoreCase("three")) {
                intermediateResult += 3;
            } else if (str.equalsIgnoreCase("four")) {
                intermediateResult += 4;
            } else if (str.equalsIgnoreCase("five")) {
                intermediateResult += 5;
            } else if (str.equalsIgnoreCase("six")) {
                intermediateResult += 6;
            } else if (str.equalsIgnoreCase("seven")) {
                intermediateResult += 7;
            } else if (str.equalsIgnoreCase("eight")) {
                intermediateResult += 8;
            } else if (str.equalsIgnoreCase("nine")) {
                intermediateResult += 9;
            } else if (str.equalsIgnoreCase("ten")) {
                intermediateResult += 10;
            } else if (str.equalsIgnoreCase("eleven")) {
                intermediateResult += 11;
            } else if (str.equalsIgnoreCase("twelve")) {
                intermediateResult += 12;
            } else if (str.equalsIgnoreCase("thirteen")) {
                intermediateResult += 13;
            } else if (str.equalsIgnoreCase("fourteen")) {
                intermediateResult += 14;
            } else if (str.equalsIgnoreCase("fifteen")) {
                intermediateResult += 15;
            } else if (str.equalsIgnoreCase("sixteen")) {
                intermediateResult += 16;
            } else if (str.equalsIgnoreCase("seventeen")) {
                intermediateResult += 17;
            } else if (str.equalsIgnoreCase("eighteen")) {
                intermediateResult += 18;
            } else if (str.equalsIgnoreCase("nineteen")) {
                intermediateResult += 19;
            } else if (str.equalsIgnoreCase("twenty")) {
                intermediateResult += 20;
            } else if (str.equalsIgnoreCase("thirty")) {
                intermediateResult += 30;
            } else if (str.equalsIgnoreCase("forty")) {
                intermediateResult += 40;
            } else if (str.equalsIgnoreCase("fifty")) {
                intermediateResult += 50;
            } else if (str.equalsIgnoreCase("sixty")) {
                intermediateResult += 60;
            } else if (str.equalsIgnoreCase("seventy")) {
                intermediateResult += 70;
            } else if (str.equalsIgnoreCase("eighty")) {
                intermediateResult += 80;
            } else if (str.equalsIgnoreCase("ninety")) {
                intermediateResult += 90;
            } else if (str.equalsIgnoreCase("hundred")) {
                intermediateResult *= 100;
            } else if (str.equalsIgnoreCase("thousand")) {
                intermediateResult *= 1000;
                finalResult += intermediateResult;
                intermediateResult = 0;
            } else if (str.equalsIgnoreCase("million")) {
                intermediateResult *= 1000000;
                finalResult += intermediateResult;
                intermediateResult = 0;
            } else if (str.equalsIgnoreCase("billion")) {
                intermediateResult *= 1000000000;
                finalResult += intermediateResult;
                intermediateResult = 0;
            } else if (str.equalsIgnoreCase("trillion")) {
                intermediateResult *= 1000000000000L;
                finalResult += intermediateResult;
                intermediateResult = 0;
            }
        }

        finalResult += intermediateResult;
        intermediateResult = 0;
        return finalResult;
    }

    @Override
    public void onError(Exception exception) {

    }

    @Override
    public void onTimeout() {

    }
}