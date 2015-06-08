/*
 * Copyright (c) 2015 the authors of j2objc-gradle (see AUTHORS file)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.github.j2objccontrib.j2objcgradle.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.InvalidUserDataException
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.incremental.IncrementalTaskInputs

/**
 * Updates the Xcode project with j2objc generated files and resources.
 * <p/>
 * This uses the CocoaPods dependency system. For more details see
 * https://cocoapods.org/.
 * <p/>
 * It creates a podspec file and inserts it into your project's pod file.
 * If you haven't create a pod file yet you have to run `pod init` in your
 * project folder before you run this task.
 */
class J2objcXcodeTask extends DefaultTask {

    // Generated ObjC source files
    @InputDirectory
    File srcGenDir

    // PodName, such as j2objc-shared
    @Input
    String getPodName() { "j2objc-${project.name}" }

    // CocoaPods podspec file that's used by the Podfile
    @OutputFile
    File getPodspecFile() { new File(project.buildDir, "${getPodName()}.podspec") }

    @OutputFile
    File getPodFile() { new File(project.j2objcConfig.xcodeProjectDir, "Podfile") }

    // j2objcConfig dependencies for UP-TO-DATE checks
    @Input
    String getJ2ObjCHome() { return J2objcUtils.j2objcHome(project) }

    @Input
    String getXcodeProjectDir() { return project.j2objcConfig.xcodeProjectDir }

    @Input
    String getXcodeTarget() { return project.j2objcConfig.xcodeTarget }


    @TaskAction
    def pod(IncrementalTaskInputs inputs) {

        if (getXcodeProjectDir() == null ||
            getXcodeTarget() == null) {
            def message =
                    "Xcode settings needs to be configured, modify in build.gradle:\n" +
                    "\n" +
                    "j2objcConfig {\n" +
                    "    xcodeProjectDir \"\${projectDir}/../Xcode\"\n" +
                    "    xcodeTarget \"<TARGET_NAME>\"\n" +
                    "}\n"
            throw new InvalidUserDataException(message)
        }

        // Resource Folder is copied to buildDir where it's accessed by the pod later
        // TODO: is it necessary to copy the files or can they be referenced in place?
        String j2objcResourceDirName = "j2objcResources"
        String j2objcResourceDirPath = "${project.buildDir}/${j2objcResourceDirName}"
        project.delete j2objcResourceDirPath
        project.copy {
            J2objcUtils.srcDirs(project, 'main', 'resources').srcDirs.each {
                from it
            }
            into j2objcResourceDirPath
        }

        // podspec paths must be relative to podspec file, which is in buildDir
        String srcGenDirRelativeToBuildDir = project.buildDir.toURI().relativize(srcGenDir.toURI())
        // File("${project.buildDir}/j2objc") => "j2objc/"

        // podspec creation
        // TODO s.libraries: this will not function for anyone who has their own list of linked libraries in the
        // compileFlags
        // Line separator assumed to be "\n" as this task can only be run on a Mac
        // TODO: Need to specify the release and debug library search paths separately.
        // Need to hook this up to the outputs of J2objcAssembleTask some how.
        String podspecFileContents =
                "Pod::Spec.new do |s|\n" +
                "s.name = '${getPodName()}'\n" +
                "s.version = '1.0'\n" +
                "s.summary = 'Generated by the j2objc-gradle plugin.'\n" +
                "s.source_files = '${srcGenDirRelativeToBuildDir}**/*.{h,m}'\n" +
                "s.public_header_files = '${srcGenDirRelativeToBuildDir}**/*.h'\n" +
                "s.resources = '${j2objcResourceDirName}/**/*'\n" +
                "s.requires_arc = true\n" +
                "s.preserve_path = '${project.buildDir}/j2objc/*.a'\n" +
                "s.libraries = 'ObjC', 'guava', 'javax_inject', 'jre_emul', 'jsr305', 'z', 'icucore', 'j2objc-generated-library'\n" +
                "s.xcconfig = { 'HEADER_SEARCH_PATHS' => '${getJ2ObjCHome()}/include', " +
                "'LIBRARY_SEARCH_PATHS' => '${getJ2ObjCHome()}/lib ${project.buildDir}/j2objc' }\n" +
                "end\n"
        logger.debug "podspecFileContents creation...\n\n" + podspecFileContents
        File podspecFile = getPodspecFile()
        podspecFile.write(podspecFileContents)

        // link the podspec in pod file
        File podFile = getPodFile()
        if (!podFile.exists()) {
            // TODO: offer to run the setup commands
            def message =
                    "No podfile exists in the directory: ${getXcodeProjectDir()}\n" +
                    "Create the Podfile in that directory with this command:\n" +
                    "\n" +
                    "(cd ${getXcodeProjectDir()} && pod init)\n" +
                    "\n" +
                    "If the pod command isn't found, then install CocoaPods:\n" +
                    "    sudo gem install cocoapods"
            throw new InvalidUserDataException(message)
        } else {
            logger.debug "Pod exists at path: ${getXcodeProjectDir()}"
            // check if this podspec has been included before
            // (a, b) = f() syntax unpacks the values in the return tuple
            def (boolean podIntegrationExists, String targetPodLine) =
            checkPodDefExistence(podFile, xcodeTarget, getPodName())

            if (!podIntegrationExists) {
                // add pod to podfile, e.g., pod 'projectName', :path => '/pathToJ2objcProject/projectName/build'
                String text = podFile.getText()
                String replacement = targetPodLine + "\n" +
                                     "pod '${getPodName()}', :path => '${project.buildDir}'"
                String newText = text.replaceFirst(targetPodLine, replacement)
                podFile.write(newText)
                logger.debug "Added pod ${getPodName()} to Xcode target ${xcodeTarget} of podfile."
            }

            // install the pod
            def output = new ByteArrayOutputStream()
            try {
                project.exec {
                    workingDir getXcodeProjectDir()
                    executable "pod"
                    args "install"
                    standardOutput output
                    errorOutput output
                }
            } catch (Exception exception) {
                logger.error output.toString()

                if (exception.getMessage().find(
                        "A problem occurred starting process 'command 'pod install''")) {
                    def message =
                            "Fix this by installing CocoaPods:\n" +
                            "    sudo gem install cocoapods \n" +
                            "\n" +
                            "See: https://cocoapods.org/"
                    throw new InvalidUserDataException(message)
                }
                // unrecognized errors are rethrown:
                throw exception
            }
            logger.debug 'Pod install output:'
            logger.debug output.toString()
        }
    }

    // checks if a pod is still integrated into a pod file
    // return: if podspec exists and the target line for insertion
    static def checkPodDefExistence(File podFile, String xcodeTarget, String podName) {
        boolean podIntegrationExists = false
        boolean isInOpenBlock = false
        List<String> lines = podFile.readLines()
        // Search for `podName` within the Xcode build target
        // `end` signifies the end of the build target
        // if the `podName` does not exist in the Xcode build target of the pod
        // it has to be added to the pod's target
        String targetPodLine = ""
        for (def line : lines) {
            if (line.contains("'${xcodeTarget}'")) {
                isInOpenBlock = true
                targetPodLine = line
            }
            if (isInOpenBlock) {
                if (line.contains(podName)) {
                    podIntegrationExists = true
                }
            }
            if (line.contains("end")) {
                if (isInOpenBlock) {
                    // TODO(confile): This line does not do anything.  What is the intended effect?
                    isInOpenBlock = false
                    break
                }
            }
        }
        return [podIntegrationExists, targetPodLine]
    }
}
