/*******************************************************************************
 * Copyright 2011 Google Inc. All Rights Reserved.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/
package com.google.appengine.eclipse.core.sdk;

import com.google.appengine.eclipse.core.AppEngineCorePlugin;
import com.google.appengine.eclipse.core.AppEngineCorePluginLog;
import com.google.gdt.eclipse.core.StatusUtilities;
import com.google.gdt.eclipse.core.extensions.ExtensionQuery;
import com.google.gdt.eclipse.core.sdk.AbstractSdk;
import com.google.gdt.eclipse.core.sdk.SdkClasspathContainer;
import com.google.gdt.eclipse.core.sdk.SdkFactory;
import com.google.gdt.eclipse.core.sdk.SdkUtils;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Status;
import org.eclipse.jdt.core.IAccessRule;
import org.eclipse.jdt.core.IClasspathAttribute;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Represents a GAE SDK and provides a URLClassLoader that can be used to load
 * the API classes.
 */
public class GaeSdk extends AbstractSdk {

  /**
   * Interface used by extensions that provide their own implementation of a GAE
   * SDK finder.
   */
  public interface ISdkPath {

    /**
     * Given an IProject, attempts to return the absolute path to the associated
     * full SDK.
     */
    IPath getSdkInstallationPath(IProject project) throws IOException, InterruptedException;
  }

  /**
   * Models a {@link GaeSdk} that is on a project's classpath either as a
   * classpath container or as raw jars.
   */
  public static class ProjectBoundSdk extends GaeSdk {

    // TODO: Put these constants in a config file
    private static final String SDK_LIB_DIR_PORTABLE_SUBPATH = "/lib/";
    private static final String SDK_LIB_USER_DIR_PORTABLE_SUBPATH = "/lib/user/";
    private static final String SDK_LIB_IMPL_DIR_PORTABLE_SUBPATH = "/lib/impl/";

    private final IJavaProject javaProject;

    public ProjectBoundSdk(IJavaProject javaProject) {
      // TODO: Clean up the class hierarchy to avoid the dummy args to
      // GaeSdk
      super("", null);
      this.javaProject = javaProject;
    }

    @Override
    public IClasspathEntry[] getClasspathEntries() {
      try {
        IClasspathEntry[] rawClasspath = javaProject.getRawClasspath();
        for (IClasspathEntry rawClasspathEntry : rawClasspath) {
          if (SdkClasspathContainer.isContainerClasspathEntry(
              GaeSdkContainer.CONTAINER_ID, rawClasspathEntry)) {
            return new IClasspathEntry[] {rawClasspathEntry};
          }
        }

        IPath installationPath = getInstallationPath();
        IClasspathEntry[] classpathEntries = new GaeSdk("", installationPath).getClasspathEntries();
        ArrayList<IClasspathEntry> arrayList = new ArrayList<IClasspathEntry>(
            Arrays.asList(rawClasspath));
        arrayList.retainAll(Arrays.asList(classpathEntries));
        // Keep only the classpath entries that are on the raw classpath
        return arrayList.toArray(NO_ICLASSPATH_ENTRIES);
      } catch (JavaModelException e) {
        AppEngineCorePluginLog.logError(e);
      }

      return AbstractSdk.NO_ICLASSPATH_ENTRIES;
    }

    /**
     * If the SDK "looks" like it has the structure of a valid GAE SDK (i.e. has
     * the appropriate libaries in their correct directories under the SDK
     * installation root), then the path to the root of the SDK installation is
     * returned.
     * 
     * If the SDK is not in the correct structure of a valid GAE SDK, but has
     * one or more of the appropriate libraries on the project's build path,
     * then the path to this library is returned.
     * 
     * In all other cases, <code>null</code> is returned.
     * 
     * TODO: Clean up the interaction between the
     * {@link #findSdkFor(IJavaProject)} method and this method; it is
     * confusing, at best, and it makes the contract of
     * {@link com.google.gdt.eclipse.core.sdk.Sdk#getInstallationPath()}
     * unclear.
     */
    @Override
    public IPath getInstallationPath() {
      try {
        IPath fragmentRootPath = null;
        IPath installPath = null;
        // Check for a type that lives appengine-api-*.jar
        IType gaeMarkerType = javaProject.findType(GAE_MARKER_TYPE);
        if (gaeMarkerType != null) {
          IPackageFragmentRoot packageFragmentRoot = (IPackageFragmentRoot) gaeMarkerType.getAncestor(IJavaElement.PACKAGE_FRAGMENT_ROOT);
          fragmentRootPath = packageFragmentRoot.getPath();

          if (fragmentRootPath.segmentCount() > 1
              && (fragmentRootPath.removeLastSegments(1).addTrailingSeparator().toPortableString().endsWith(
              SDK_LIB_USER_DIR_PORTABLE_SUBPATH)
              || fragmentRootPath.removeLastSegments(
                  1).addTrailingSeparator().toPortableString().endsWith(
                  SDK_LIB_IMPL_DIR_PORTABLE_SUBPATH))) {
            // Should live in SDK_ROOT/lib/user or SDK_ROOT/lib/impl
            installPath = fragmentRootPath.removeLastSegments(3);
          }
        } else {
          // Check for a type that lives in appengine-tools-api.jar
          gaeMarkerType = javaProject.findType(GAE_TOOLS_MARKER_TYPE);
          if (gaeMarkerType != null) {
            IPackageFragmentRoot packageFragmentRoot = (IPackageFragmentRoot) gaeMarkerType.getAncestor(IJavaElement.PACKAGE_FRAGMENT_ROOT);
            fragmentRootPath = packageFragmentRoot.getPath();
            if (fragmentRootPath.segmentCount() > 1
                && fragmentRootPath.removeLastSegments(1).addTrailingSeparator().toPortableString().endsWith(
                SDK_LIB_DIR_PORTABLE_SUBPATH)) {
              // Should live in SDK_ROOT/lib
              installPath = fragmentRootPath.removeLastSegments(2);
            }
          }
        }

        if (installPath != null) {
          return installPath;
        }

        return fragmentRootPath;
      } catch (JavaModelException e) {
        AppEngineCorePluginLog.logError(e);
      }

      return null;
    }

    @Override
    public String getName() {
      IPath installationPath = getInstallationPath();
      if (installationPath != null) {
        return installationPath.toOSString();
      } else {
        return "Unknown";
      }
    }
  }

  /**
   * appengine-api marker type.
   */
  public static final String GAE_MARKER_TYPE =
      "com.google.appengine.api.datastore.DatastoreService";
  /**
   * appengine-tools-api marker type.
   */
  public static final String GAE_TOOLS_MARKER_TYPE = "com.google.appengine.tools.info.SdkInfo";

  public static final IPath APPENGINE_TOOLS_API_JAR_PATH = new Path(
      "lib/appengine-tools-api.jar");

  private static final SdkFactory<GaeSdk> factory = new SdkFactory<GaeSdk>() {
    public GaeSdk newInstance(String name, IPath sdkHome) {
      return new GaeSdk(name, sdkHome);
    }
  };

  private static final File[] NO_FILES = new File[0];

  /**
   * Returns a {@link GaeSdk} associated with the project or <code>null</code>
   * if the project was not associated with an
   * {@link com.google.gdt.eclipse.core.sdk.Sdk}.
   * 
   * @param javaProject project that might be associated with a {@link GaeSdk}
   * @return {@link GaeSdk} instance associated with the project or <code>null
   *         </code>
   * @throws JavaModelException if this project does not exist or if an
   *           exception occurs while accessing its corresponding resource
   */
  public static GaeSdk findSdkFor(IJavaProject javaProject) throws JavaModelException {
    ExtensionQuery<GaeSdk.ISdkPath> extQuery =
        new ExtensionQuery<GaeSdk.ISdkPath>(AppEngineCorePlugin.PLUGIN_ID, "gaeSdk", "class");
    List<ExtensionQuery.Data<GaeSdk.ISdkPath>> gaeSdks = extQuery.getData();
    for (ExtensionQuery.Data<GaeSdk.ISdkPath> sdk : gaeSdks) {
      IPath fullSdkPath = null;
      try {
        fullSdkPath = sdk.getExtensionPointData().getSdkInstallationPath(javaProject.getProject());
      } catch (IOException e) {
        AppEngineCorePluginLog.logError(e);
      } catch (InterruptedException e) {
        AppEngineCorePluginLog.logError(e);
      }
      if (fullSdkPath != null) {
        return GaeSdk.getFactory().newInstance(fullSdkPath.toOSString(), fullSdkPath);
      }
    }

    ProjectBoundSdk projectBoundSdk = new ProjectBoundSdk(javaProject);
    if (projectBoundSdk.getInstallationPath() != null) {
      return projectBoundSdk;
    }
    return null;
  }

  public static SdkFactory<GaeSdk> getFactory() {
    return factory;
  }

  private static void validateAllFilesExist(List<File> files)
      throws CoreException {
    for (File file : files) {
      if (!file.exists()) {
        throw new CoreException(new Status(IStatus.ERROR,
            AppEngineCorePlugin.PLUGIN_ID, "SDK is missing file "
                + file.getAbsolutePath()));
      }
    }
  }

  private Set<GaeSdkCapability> capabilities;

  private GaeSdk(String name, IPath location) {
    super(name, location);
  }

  public AppEngineBridge getAppEngineBridge() throws CoreException {
    return AppEngineBridgeFactory.getAppEngineBridge(getInstallationPath());
  }

  public AppEngineBridge getAppEngineBridgeForDeploy() throws CoreException {
    /**
     * GAE SDK 1.4.3 includes support for deploying to appengine using oauth
     * authentication. If the SDK this project is using is older than 1.4.3,
     * use the appengine-tool-api jar bundled with GPE rather than the one
     * in the GAE SDK. 
     */
    if (SdkUtils.compareVersionStrings(getVersion(), "1.4.3") >= 0) {
      return AppEngineBridgeFactory.getAppEngineBridge(getInstallationPath());
    } else {
      return AppEngineBridgeFactory.createBridgeWithBundledToolsJar(getInstallationPath());
    }
  }
  
  /**
   * 
   * Returns the set of capabilities supported by this SDK.
   */
  public Set<GaeSdkCapability> getCapabilities() {
    synchronized (this) {
      if (capabilities == null) {
        capabilities = doGetCapabilities();
      }
    }

    return capabilities;
  }

  public IClasspathEntry[] getClasspathEntries() {
    try {
      AppEngineBridge appEngineBridge = AppEngineBridgeFactory.getAppEngineBridge(getInstallationPath());
      List<File> classpathFiles = appEngineBridge.getBuildclasspathFiles();

      IPath javadocLocation = getInstallationPath().append(
          new Path("docs/javadoc"));
      IClasspathAttribute[] extraAttributes = new IClasspathAttribute[0];

      if (javadocLocation.toFile().exists()) {
        extraAttributes = new IClasspathAttribute[] {JavaCore.newClasspathAttribute(
            IClasspathAttribute.JAVADOC_LOCATION_ATTRIBUTE_NAME,
            javadocLocation.toFile().toURI().toString())};
      }

      List<IClasspathEntry> buildpath = new ArrayList<IClasspathEntry>();

      // TODO: This approach only adds the source to the ORM jars,
      // for the moment (these are the only jars with separate source provided).
      // We'll need a more general approach to finding the right source
      // attachment later.
      for (File file : classpathFiles) {
        IPath path = new Path(file.getAbsolutePath());

        String possibleSourceName = file.getName().replace(".jar", "-src.zip");
        IPath possibleSource = getInstallationPath().append("src/orm").append(
            possibleSourceName);

        IPath sourcePath = possibleSource.toFile().exists() ? possibleSource
            : null;

        buildpath.add(JavaCore.newLibraryEntry(path, sourcePath, null,
            new IAccessRule[0], extraAttributes, false));
      }

      return buildpath.toArray(NO_ICLASSPATH_ENTRIES);
    } catch (CoreException e) {
      // Validate method will tell you what is wrong.
      return NO_ICLASSPATH_ENTRIES;
    }
  }

  public String getVersion() {
    try {
      AppEngineBridge bridge = getAppEngineBridge();
      return SdkUtils.cleanupVersion(bridge.getSdkVersion());
    } catch (CoreException e) {
      // Validate method will tell you what is wrong.
      return "";
    }
  }

  public File[] getWebAppClasspathFiles() {
    try {
      AppEngineBridge appEngineBridge = AppEngineBridgeFactory.getAppEngineBridge(getInstallationPath());
      List<File> userLibFiles = new ArrayList<File>(appEngineBridge.getUserLibFiles());      
      String filePath = getInstallationPath() +  
          AppEngineBridge.APPENGINE_CLOUD_SQL_JAR_PATH_IN_SDK +
          AppEngineBridge.APPENGINE_CLOUD_SQL_JAR;
      userLibFiles.add(new File(filePath));
      return userLibFiles.toArray(NO_FILES);
    } catch (CoreException e) {
      // Validate method will tell you what is wrong.
      return NO_FILES;
    }
  }

  public Set<String> getWhiteList() {
    AppEngineBridge bridge;
    try {
      bridge = AppEngineBridgeFactory.getAppEngineBridge(getInstallationPath());
    } catch (CoreException e) {
      // Validate method will tell you what is wrong.
      return Collections.emptySet();
    }

    return bridge.getWhiteList();
  }

  public IStatus validate() {
    try {
      // Additional validation
      if (getInstallationPath() == null) {
        return StatusUtilities.newErrorStatus(
            "Could not determine an installation path based on the project's classpath.",
            AppEngineCorePlugin.PLUGIN_ID);
      }

      AppEngineBridge appEngineBridge = getAppEngineBridge();
      validateAllFilesExist(appEngineBridge.getBuildclasspathFiles());
      validateAllFilesExist(appEngineBridge.getSharedLibFiles());
      validateAllFilesExist(appEngineBridge.getToolsLibFiles());
      validateAllFilesExist(appEngineBridge.getUserLibFiles());
      return Status.OK_STATUS;
    } catch (CoreException e) {
      return e.getStatus();
    } catch (RuntimeException e) {
      return new Status(IStatus.ERROR, AppEngineCorePlugin.PLUGIN_ID,
          e.getLocalizedMessage(), e);
    }
  }

  private Set<GaeSdkCapability> doGetCapabilities() {
    if (!validate().isOK()) {
      return Collections.emptySet();
    }

    Set<GaeSdkCapability> caps = EnumSet.noneOf(GaeSdkCapability.class);
    for (GaeSdkCapability capability : GaeSdkCapability.values()) {
      if (capability.check(this)) {
        caps.add(capability);
      }
    }

    return caps;
  }
}
