
package com.atakmap.android.meshtastic.plugin;


import com.atak.plugins.impl.AbstractPlugin;
import com.atak.plugins.impl.PluginContextProvider;
import com.atakmap.android.meshtastic.MeshtasticMapComponent;
import gov.tak.api.plugin.IServiceController;

public class MeshtasticLifecycle extends AbstractPlugin {
    public MeshtasticLifecycle(IServiceController serviceController) {
        super(serviceController, new MeshtasticTool(serviceController.getService(PluginContextProvider.class).getPluginContext()), new MeshtasticMapComponent());

    }
}
