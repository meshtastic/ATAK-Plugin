package com.atakmap.android.meshtastic;

import static com.atakmap.android.maps.MapView._mapView;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.preference.PreferenceManager;
import android.speech.tts.TextToSpeech;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.atakmap.android.dropdown.DropDown;
import com.atakmap.android.dropdown.DropDownReceiver;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.meshtastic.plugin.R;
import com.atakmap.coremap.log.Log;
import com.geeksville.mesh.ATAKProtos;
import com.geeksville.mesh.DataPacket;
import com.geeksville.mesh.MeshProtos;
import com.geeksville.mesh.MessageStatus;
import com.geeksville.mesh.Portnums;
import com.google.protobuf.ByteString;

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
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private Button voiceMemoBtn;
    private Model model;
    public SpeechService speechService;
    private TextView tv;
    private int toggle = 0;
    private View.OnKeyListener keyListener;
    public static TextToSpeech t1;
    private SharedPreferences prefs;


    protected MeshtasticDropDownReceiver(final MapView mapView, final Context context) {
        super(mapView);
        this.pluginContext = context;
        this.appContext = mapView.getContext();
        this.mapView = mapView;
        this.prefs = PreferenceManager.getDefaultSharedPreferences(mapView.getContext().getApplicationContext());

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
        int permissionCheck = ContextCompat.checkSelfPermission(mapView.getContext().getApplicationContext(), Manifest.permission.RECORD_AUDIO);
        if (permissionCheck != PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "REC AUDIO DENIED");
        } else {
            try {
                Log.d(TAG, "REC AUDIO GRANTED");
                initModel();
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
            int pttKey = prefs.getInt("plugin_meshtastic_ptt", 0);
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
        copyFile(assetManager, sourcePath + "/uuid", targetDir);

        this.model = new Model(resultPath);
        Log.d(TAG, "Model ready");

    }

    private static String readLine(InputStream is) throws IOException {
        return new BufferedReader(new InputStreamReader(is)).readLine();
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

    private static void copyFile(AssetManager assetManager, String fileName, File outPath) throws IOException {
        InputStream in;
        in = assetManager.open(fileName);
        OutputStream out = new FileOutputStream(outPath + "/" + fileName);

        byte[] buffer = new byte[4000];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        in.close();
        out.close();
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

    @Override
    protected void disposeImpl() {
        mapView.removeOnKeyListener(keyListener);

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

        DataPacket dp = new DataPacket(DataPacket.ID_BROADCAST, MeshProtos.Data.newBuilder().setPayload(payload).build().toByteArray(),Portnums.PortNum.TEXT_MESSAGE_APP_VALUE, DataPacket.ID_LOCAL, System.currentTimeMillis(), 0, MessageStatus.UNKNOWN, 3, 0);
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