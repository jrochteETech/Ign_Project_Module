package com.etech.project;

import java.io.InputStream;

import com.inductiveautomation.ignition.common.project.ProjectFileUtil;

public final class ProjectValidator {
    public static void main(String[] arguments) throws Exception {
        ProjectBundle bundle = ProjectBundle.load(ProjectValidator.class.getClassLoader(), arguments[0]);
        if (!bundle.frameworkOnly()) {
            try (InputStream input = bundle.openArchive()) {
                ProjectFileUtil.importFromZip(input, bundle.projectName());
            }
        }
        System.out.println("Validated " + bundle.moduleId() + " for Ignition " + bundle.target()
                + (bundle.frameworkOnly() ? " (framework only)" : " (bundled Perspective project)"));
    }
}