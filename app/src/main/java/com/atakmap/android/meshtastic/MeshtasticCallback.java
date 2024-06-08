package com.atakmap.android.meshtastic;

import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.widget.Toast;

import com.atakmap.android.maps.MapView;
import com.atakmap.android.missionpackage.api.SaveAndSendCallback;
import com.atakmap.android.missionpackage.file.MissionPackageManifest;
import com.atakmap.android.missionpackage.file.task.MissionPackageBaseTask;
import com.atakmap.coremap.filesystem.FileSystemUtils;
import com.atakmap.coremap.log.Log;
import com.geeksville.mesh.ConfigProtos;
import com.geeksville.mesh.DataPacket;
import com.geeksville.mesh.LocalOnlyProtos;
import com.geeksville.mesh.MessageStatus;
import com.geeksville.mesh.Portnums;
import com.google.protobuf.InvalidProtocolBufferException;

import java.io.File;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;

public class MeshtasticCallback implements SaveAndSendCallback {
    private final static String TAG = "MeshtasticCallback";
    @Override
    public void onMissionPackageTaskComplete(MissionPackageBaseTask missionPackageBaseTask, boolean success) {

        Log.d(TAG, "onMissionPackageTaskComplete: " + success);

        MissionPackageManifest missionPackageManifest = missionPackageBaseTask.getManifest();

        File file = new File(missionPackageManifest.getPath());
        Log.d(TAG, file.getAbsolutePath());

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(MapView.getMapView().getContext());
        SharedPreferences.Editor editor = prefs.edit();

        if (FileSystemUtils.isFile(file)) {
            // check file size
            if (FileSystemUtils.getFileSize(file) > 1024 * 56) {
                Toast.makeText(MapView.getMapView().getContext(), "File is too large to send, 56KB Max", Toast.LENGTH_LONG).show();
                editor.putBoolean("plugin_meshtastic_file_transfer", false);
                return;
            } else {
                Log.d(TAG, "File is small enough to send: " + FileSystemUtils.getFileSize(file));

                // flag to indicate we are in a file transfer mode
                editor.putBoolean("plugin_meshtastic_file_transfer", true);
                editor.apply();

                // capture node's config
                byte[] config = MeshtasticMapComponent.getConfig();
                Log.d(TAG, "Config Size: " + config.length);
                LocalOnlyProtos.LocalConfig c;
                try {
                    c = LocalOnlyProtos.LocalConfig.parseFrom(config);
                } catch (InvalidProtocolBufferException e) {
                    throw new RuntimeException(e);
                }
                Log.d(TAG, "Config: " + c.toString());
                ConfigProtos.Config.LoRaConfig lc = c.getLora();

                // retain old modem preset
                int oldModemPreset = lc.getModemPreset().getNumber();

                // configure short/fast mode
                ConfigProtos.Config.Builder configBuilder = ConfigProtos.Config.newBuilder();
                AtomicReference<ConfigProtos.Config.LoRaConfig.Builder> loRaConfigBuilder = new AtomicReference<>(lc.toBuilder());
                AtomicReference<ConfigProtos.Config.LoRaConfig.ModemPreset> modemPreset = new AtomicReference<>(ConfigProtos.Config.LoRaConfig.ModemPreset.forNumber(ConfigProtos.Config.LoRaConfig.ModemPreset.SHORT_FAST_VALUE));
                loRaConfigBuilder.get().setModemPreset(modemPreset.get());
                configBuilder.setLora(loRaConfigBuilder.get());
                boolean needReboot;

                // if not already in short/fast mode, switch to it
                if (oldModemPreset != ConfigProtos.Config.LoRaConfig.ModemPreset.SHORT_FAST_VALUE) {
                    Toast.makeText(MapView.getMapView().getContext(), "Rebooting to Short/Fast for file transfer", Toast.LENGTH_LONG).show();
                    needReboot = true;
                } else {
                    needReboot = false;
                }

                new Thread(() -> {

                    // send out file transfer command
                    int channel = prefs.getInt("plugin_meshtastic_channel", 0);
                    int messageId = ThreadLocalRandom.current().nextInt(0x10000000, 0x7fffff00);
                    Log.d(TAG, "Switch Message ID: " + messageId);
                    editor.putInt("plugin_meshtastic_switch_id", messageId);

                    // flag to indicate we are waiting for remote nodes to ACK our SWT command
                    editor.putBoolean("plugin_meshtastic_switch_ACK", true);
                    editor.apply();

                    Log.d(TAG, "Broadcasting switch command");
                    DataPacket dp = new DataPacket(DataPacket.ID_BROADCAST, new byte[]{'S', 'W', 'T'}, Portnums.PortNum.ATAK_FORWARDER_VALUE, DataPacket.ID_LOCAL, System.currentTimeMillis(), messageId, MessageStatus.UNKNOWN, 3, channel);
                    MeshtasticMapComponent.sendToMesh(dp);

                    // wait for the remote nodes to ACK our switch command
                    while (prefs.getBoolean("plugin_meshtastic_switch_ACK", false)) {
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        }
                    }

                    // we gotta reboot to short/fast
                    if (needReboot) {
                        // give the remote nodes a little extra time to reboot
                        try {
                            Thread.sleep(2000);
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        }

                        try {
                            // flag to indicate we are waiting for a reboot into short/fast
                            editor.putBoolean("plugin_meshtastic_shortfast", true);
                            editor.apply();

                            MeshtasticMapComponent.setConfig(configBuilder.build().toByteArray());

                            // wait for ourselves to switch to short/fast
                            while (prefs.getBoolean("plugin_meshtastic_shortfast", false))
                                Thread.sleep(1000);

                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }

                    try {
                        // ready to send file
                        if (MeshtasticMapComponent.sendFile(file)) {
                            Log.d(TAG, "File sent successfully");

                            // wait for at least one ACK from a recipient
                            while (prefs.getBoolean("plugin_meshtastic_file_transfer", false))
                                Thread.sleep(1000);
                        } else {
                            Log.d(TAG, "File send failed");
                        }

                        if (needReboot) {
                            // restore config
                            Log.d(TAG, "Restoring previous modem preset");
                            loRaConfigBuilder.set(lc.toBuilder());
                            modemPreset.set(ConfigProtos.Config.LoRaConfig.ModemPreset.forNumber(oldModemPreset));
                            loRaConfigBuilder.get().setModemPreset(modemPreset.get());
                            configBuilder.setLora(loRaConfigBuilder.get());
                            MeshtasticMapComponent.setConfig(configBuilder.build().toByteArray());
                        }
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }).start();
            }
        } else {
            Log.d(TAG, "Invalid file");
        }
    }
}
