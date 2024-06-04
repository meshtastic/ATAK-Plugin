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

        if (FileSystemUtils.isFile(file)) {
            // check file size
            if (FileSystemUtils.getFileSize(file) > 1024 * 56) {
                Toast.makeText(MapView.getMapView().getContext(), "File is too large to send, 56KB Max", Toast.LENGTH_LONG).show();
                return;
            } else {
                Log.d(TAG, "File is small enough to send: " + FileSystemUtils.getFileSize(file));
                Toast.makeText(MapView.getMapView().getContext(), "Switching to Short/Fast for file transfer", Toast.LENGTH_LONG).show();
                Log.d(TAG, "Broadcasting switch command");
                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(MapView.getMapView().getContext());
                SharedPreferences.Editor editor = prefs.edit();
                editor.putBoolean("plugin_meshtastic_file_transfer", true);
                editor.putBoolean("plugin_meshtastic_switch_ACK", true);
                int channel = prefs.getInt("plugin_meshtastic_channel", 0);
                int messageId = ThreadLocalRandom.current().nextInt(0x10000000, 0x7fffff00);
                Log.d(TAG, "Message ID: " + messageId);
                editor.putInt("plugin_meshtastic_message_id", messageId);
                editor.apply();

                DataPacket dp = new DataPacket(DataPacket.ID_BROADCAST, new byte[]{'S', 'W', 'T'}, Portnums.PortNum.ATAK_FORWARDER_VALUE, DataPacket.ID_LOCAL, System.currentTimeMillis(), messageId, MessageStatus.UNKNOWN, 3, channel);
                MeshtasticMapComponent.sendToMesh(dp);

                new Thread(() -> {
                    try {
                        while (prefs.getBoolean("plugin_meshtastic_switch_ACK", false)) {
                            try {
                                Thread.sleep(1000);
                            } catch (InterruptedException e) {
                                e.printStackTrace();
                            }
                        }

                        // give the remote node time to reboot
                        Thread.sleep(2000);

                        //capture local config
                        byte[] config = MeshtasticMapComponent.getConfig();
                        Log.d(TAG, "Config Size: " + config.length);
                        LocalOnlyProtos.LocalConfig c = LocalOnlyProtos.LocalConfig.parseFrom(config);
                        Log.d(TAG, "Config: " + c.toString());
                        ConfigProtos.Config.LoRaConfig lc = c.getLora();

                        int oldModemPreset = lc.getModemPreset().getNumber();

                        // set short/fast for file transfer
                        ConfigProtos.Config.Builder configBuilder = ConfigProtos.Config.newBuilder();
                        AtomicReference<ConfigProtos.Config.LoRaConfig.Builder> loRaConfigBuilder = new AtomicReference<>(lc.toBuilder());
                        AtomicReference<ConfigProtos.Config.LoRaConfig.ModemPreset> modemPreset = new AtomicReference<>(ConfigProtos.Config.LoRaConfig.ModemPreset.forNumber(ConfigProtos.Config.LoRaConfig.ModemPreset.SHORT_FAST_VALUE));
                        loRaConfigBuilder.get().setModemPreset(modemPreset.get());
                        configBuilder.setLora(loRaConfigBuilder.get());
                        MeshtasticMapComponent.setConfig(configBuilder.build().toByteArray());

                        editor.putBoolean("plugin_meshtastic_shortfast", true);
                        editor.apply();

                        // wait for ourselves to switch to short/fast
                        while(prefs.getBoolean("plugin_meshtastic_shortfast", false))
                            Thread.sleep(1000);

                        // the device has rebooted, send the file
                        if (MeshtasticMapComponent.sendFile(file)) {
                            Log.d(TAG, "File sent successfully");
                            // wait for at least one ACK from a recipient
                            while(prefs.getBoolean("plugin_meshtastic_file_transfer", false))
                                    Thread.sleep(1000);
                        } else {
                            Log.d(TAG, "File send failed");
                        }

                        // restore config
                        Log.d(TAG, "Restoring previous modem preset");
                        loRaConfigBuilder.set(lc.toBuilder());
                        modemPreset.set(ConfigProtos.Config.LoRaConfig.ModemPreset.forNumber(oldModemPreset));
                        loRaConfigBuilder.get().setModemPreset(modemPreset.get());
                        configBuilder.setLora(loRaConfigBuilder.get());
                        MeshtasticMapComponent.setConfig(configBuilder.build().toByteArray());
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    } catch (InvalidProtocolBufferException e) {
                        e.printStackTrace();
                    }
                }).start();

            }

        } else {
            Log.d(TAG, "Invalid file");
        }
    }
}
