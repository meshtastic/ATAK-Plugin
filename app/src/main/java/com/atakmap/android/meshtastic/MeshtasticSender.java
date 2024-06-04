package com.atakmap.android.meshtastic;

import android.content.Context;
import android.graphics.drawable.Drawable;

import com.atakmap.android.importexport.send.MissionPackageSender;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.missionpackage.MissionPackageMapComponent;
import com.atakmap.android.missionpackage.file.MissionPackageManifest;
import com.atakmap.android.missionpackage.file.task.MissionPackageBaseTask;
import com.atakmap.android.meshtastic.plugin.R;
import com.atakmap.coremap.log.Log;


public class MeshtasticSender extends MissionPackageSender {

    private final static String TAG = "MeshtasticSender";
    private Context pluginContext;
    private MapView view;
    public MeshtasticSender(MapView view, Context pluginContext) {
        super(view);
        this.view = view;
        this.pluginContext = pluginContext;
    }

    @Override
    public boolean sendMissionPackage(MissionPackageManifest missionPackageManifest, MissionPackageBaseTask.Callback callback, Callback callback1) {
        Log.d(TAG, "sendMissionPackage");
        Log.d(TAG, missionPackageManifest.toString());
        MissionPackageMapComponent.getInstance().getFileIO().save(missionPackageManifest, true, new MeshtasticCallback());
        return true;
    }

    @Override
    public String getName() {
        return "Meshtastic";
    }

    @Override
    public Drawable getIcon() {
        return pluginContext.getDrawable(R.drawable.ic_launcher);
    }
}
