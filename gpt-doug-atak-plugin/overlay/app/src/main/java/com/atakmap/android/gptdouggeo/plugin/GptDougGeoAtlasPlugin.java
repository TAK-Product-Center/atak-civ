package com.atakmap.android.gptdouggeo.plugin;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import com.atak.plugins.impl.PluginContextProvider;
import com.atak.plugins.impl.PluginLayoutInflater;
import gov.tak.api.plugin.IPlugin;
import gov.tak.api.plugin.IServiceController;
import gov.tak.api.ui.IHostUIService;
import gov.tak.api.ui.Pane;
import gov.tak.api.ui.PaneBuilder;
import gov.tak.api.ui.ToolbarItem;
import gov.tak.api.ui.ToolbarItemAdapter;
import gov.tak.platform.marshal.MarshalManager;

/** Local-only civilian demonstration; never opens transport or reads TAK location. */
public final class GptDougGeoAtlasPlugin implements IPlugin {
    private final Context pluginContext;
    private final IHostUIService uiService;
    private final ToolbarItem toolbarItem;
    private Pane pane;

    public GptDougGeoAtlasPlugin(IServiceController controller) {
        PluginContextProvider provider = controller.getService(PluginContextProvider.class);
        pluginContext = provider == null ? null : provider.getPluginContext();
        uiService = controller.getService(IHostUIService.class);
        if (pluginContext == null) {
            toolbarItem = null;
            return;
        }
        pluginContext.setTheme(R.style.ATAKPluginTheme);
        toolbarItem = new ToolbarItem.Builder(
                pluginContext.getString(R.string.app_name),
                MarshalManager.marshal(
                        pluginContext.getResources().getDrawable(R.drawable.ic_launcher),
                        android.graphics.drawable.Drawable.class,
                        gov.tak.api.commons.graphics.Bitmap.class))
                .setListener(new ToolbarItemAdapter() {
                    @Override public void onClick(ToolbarItem item) { showPane(); }
                }).setIdentifier(pluginContext.getPackageName() + ".gptdouggeo").build();
    }

    @Override public void onStart() {
        if (uiService != null && toolbarItem != null) uiService.addToolbarItem(toolbarItem);
    }

    @Override public void onStop() {
        if (uiService != null && toolbarItem != null) uiService.removeToolbarItem(toolbarItem);
    }

    private void showPane() {
        if (uiService == null || pluginContext == null) return;
        if (pane == null) {
            View view = PluginLayoutInflater.inflate(pluginContext, R.layout.main_layout, null);
            ViewGroup preview = view.findViewById(R.id.synthetic_preview_container);
            if (preview != null) {
                int height = (int)(pluginContext.getResources().getDisplayMetrics().density * 240);
                preview.addView(new GeoAtlasMiniMapView(pluginContext),
                        new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height));
            }
            pane = new PaneBuilder(view)
                    .setMetaValue(Pane.RELATIVE_LOCATION, Pane.Location.Default)
                    .setMetaValue(Pane.PREFERRED_WIDTH_RATIO, 0.6D)
                    .setMetaValue(Pane.PREFERRED_HEIGHT_RATIO, 0.5D)
                    .build();
        }
        if (!uiService.isPaneVisible(pane)) uiService.showPane(pane, null);
    }
}
