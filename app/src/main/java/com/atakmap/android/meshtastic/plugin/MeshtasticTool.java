package com.atakmap.android.meshtastic.plugin;

import android.content.Context;

import com.atak.plugins.impl.AbstractPluginTool;

import gov.tak.api.util.Disposable;

public class MeshtasticTool extends AbstractPluginTool implements Disposable {

    public MeshtasticTool(Context context) {
        super(context,
                context.getString(R.string.app_name),
                context.getString(R.string.app_name),
                context.getResources().getDrawable(R.drawable.mesh_logo_playstore),
                "com.atakmap.android.meshtastic.SHOW_PLUGIN");
        PluginNativeLoader.init(context);
    }

    @Override
    public void dispose() {
    }

}