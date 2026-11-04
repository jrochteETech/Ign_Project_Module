package com.etech.project;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.ArrayList;

import com.inductiveautomation.ignition.common.licensing.LicenseState;
import com.inductiveautomation.ignition.common.project.ProjectFileUtil;
import com.inductiveautomation.ignition.common.project.ProjectImport;
import com.inductiveautomation.ignition.gateway.model.AbstractGatewayModuleHook;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;

public final class ProjectGatewayHook extends AbstractGatewayModuleHook {
    private GatewayContext context;
    private ProjectBundle bundle;

    @Override
    public void setup(GatewayContext context) {
        this.context = context;
        try {
            bundle = ProjectBundle.load(getClass().getClassLoader(), "8.1");
        } catch (Exception failure) {
            throw new IllegalStateException("Invalid bundled project metadata", failure);
        }
    }

    @Override
    public void startup(LicenseState activationState) {
        if (bundle.frameworkOnly()) return;
        if (context.getModule("com.inductiveautomation.perspective") == null) {
            throw new IllegalStateException("The Perspective module must be installed");
        }
        try (InputStream project = bundle.openArchive()) {
            ProjectImport imported = ProjectFileUtil.importFromZip(project, bundle.projectName());
            Path stateDirectory = context.getSystemManager().getDataDir().toPath()
                    .resolve("etech-project-modules").resolve(bundle.moduleId());
            ManagedDeployment.deploy(bundle, stateDirectory, new ManagedDeployment.ProjectAccess() {
                public boolean exists() {
                    return context.getProjectManager().getProjectNames().contains(bundle.projectName());
                }

                public void create() throws Exception {
                    context.getProjectManager().createProject(imported.getName(), imported.getManifest(),
                            new ArrayList<>(imported.getResources()));
                }

                public void replace() throws Exception {
                    context.getProjectManager().createOrReplaceProject(imported.getName(), imported.getManifest(),
                            new ArrayList<>(imported.getResources()));
                }

                public void export(OutputStream destination) throws Exception {
                    context.getProjectManager().exportProject(bundle.projectName(), destination);
                }

                public void validateBackup(Path backup) throws Exception {
                    ProjectFileUtil.importFromZip(backup.toFile(), bundle.projectName());
                }

                public void restore(Path backup) throws Exception {
                    ProjectImport previous = ProjectFileUtil.importFromZip(backup.toFile(), bundle.projectName());
                    context.getProjectManager().createOrReplaceProject(previous.getName(), previous.getManifest(),
                            new ArrayList<>(previous.getResources()));
                }
            });
        } catch (Exception failure) {
            throw new IllegalStateException("Unable to deploy owned project " + bundle.projectName(), failure);
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