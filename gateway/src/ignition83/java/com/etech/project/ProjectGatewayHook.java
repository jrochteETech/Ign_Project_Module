package com.etech.project;

import java.io.InputStream;

import com.inductiveautomation.ignition.common.licensing.LicenseState;
import com.inductiveautomation.ignition.common.project.ProjectFileUtil;
import com.inductiveautomation.ignition.gateway.model.AbstractGatewayModuleHook;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;

public final class ProjectGatewayHook extends AbstractGatewayModuleHook {
    private GatewayContext context;
    private ProjectBundle bundle;

    @Override
    public void setup(GatewayContext context) {
        this.context = context;
        try {
            bundle = ProjectBundle.load(getClass().getClassLoader(), "8.3");
        } catch (Exception failure) {
            throw new IllegalStateException("Invalid bundled project metadata", failure);
        }
    }

    @Override
    public void startup(LicenseState activationState) {
        if (bundle.frameworkOnly()) return;
        if (context.getProjectManager().getNames().contains(bundle.projectName())) {
            throw new IllegalStateException("Refusing to shadow an existing project: " + bundle.projectName());
        }
        try (InputStream project = bundle.openArchive()) {
            context.getProjectManager().addImmutableProject(
                    ProjectFileUtil.importFromZip(project, bundle.projectName()));
        } catch (Exception failure) {
            throw new IllegalStateException("Unable to register immutable project " + bundle.projectName(), failure);
        }
    }

    @Override
    public void shutdown() {
    }

    @Override
    public boolean isFreeModule() {
        return true;
    }
}