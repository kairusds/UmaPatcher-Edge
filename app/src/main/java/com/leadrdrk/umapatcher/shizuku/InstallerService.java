package com.leadrdrk.umapatcher.shizuku;

import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.os.IBinder;
import android.os.RemoteException;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import com.leadrdrk.umapatcher.shizuku.IInstallerService;

public class InstallerService extends IInstallerService.Stub {

    private static Object getPackageManager() throws Exception {
        Class<?> serviceManagerClass = Class.forName("android.os.ServiceManager");
        Method getServiceMethod = serviceManagerClass.getDeclaredMethod("getService", String.class);
        IBinder binder = (IBinder) getServiceMethod.invoke(null, "package");
        Class<?> stubClass = Class.forName("android.content.pm.IPackageManager$Stub");
        Method asInterfaceMethod = stubClass.getDeclaredMethod("asInterface", IBinder.class);
        return asInterfaceMethod.invoke(null, binder);
    }

    private static Object getPackageInstaller(Object iPackageManager) throws Exception {
        Method getPackageInstallerMethod = iPackageManager.getClass().getMethod("getPackageInstaller");
        return getPackageInstallerMethod.invoke(iPackageManager);
    }

    @Override
    public String install(List<String> apkPaths) throws RemoteException {
        Object iPackageManager;
        Object iPackageInstaller;

        try {
            iPackageManager = getPackageManager();
            if(iPackageManager == null) return "Failed to get PackageManager service.";

            iPackageInstaller = getPackageInstaller(iPackageManager);
            if(iPackageInstaller == null) return "Failed to get PackageInstaller service.";
        }catch(Exception e) {
            return "Failed to get system services: " + e.getMessage();
        }

        PackageInstaller.Session session = null;
        try {
            PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            try {
                Field installFlags = params.getClass().getDeclaredField("installFlags");
                installFlags.setAccessible(true);
                installFlags.setInt(params, 0x00000002 /* INSTALL_REPLACE_EXISTING */);
            } catch (Exception e) {
                System.err.println("Failed to set installFlags: " + e.getMessage());
            }

            String installerPackageName = "com.android.vending";
            if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                try {
                    Method setPackageSourceMethod = params.getClass().getMethod("setPackageSource", int.class);
                    setPackageSourceMethod.invoke(params, PackageInstaller.PACKAGE_SOURCE_STORE);
                }catch (Exception e) {
                    System.err.println("Failed to set package source: " + e.getMessage());
                }
            }
            if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                params.setInstallerPackageName(installerPackageName);
            }

            int sessionId;
            int userId = 0;
            Class<?> iPackageInstallerClass = iPackageInstaller.getClass();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Method createSessionMethod = iPackageInstallerClass.getMethod("createSession", PackageInstaller.SessionParams.class, String.class, String.class, int.class);
                sessionId = (int) createSessionMethod.invoke(iPackageInstaller, params, installerPackageName, installerPackageName, userId);
            } else {
                Method createSessionMethod = iPackageInstallerClass.getMethod("createSession", PackageInstaller.SessionParams.class, String.class, int.class);
                sessionId = (int) createSessionMethod.invoke(iPackageInstaller, params, installerPackageName, userId);
            }

            PackageInstaller packageInstaller;
            try {
                IBinder installerBinder = (IBinder) iPackageInstaller.getClass().getMethod("asBinder").invoke(iPackageInstaller);
                Class<?> packageInstallerClass = Class.forName("android.content.pm.PackageInstaller");
                if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Constructor constructor = packageInstallerClass.getConstructor(Class.forName("android.content.pm.IPackageInstaller"), String.class, String.class, int.class);
                    packageInstaller = (PackageInstaller) constructor.newInstance(iPackageInstaller, "com.android.shell", null, 0);
                }else {
                    Constructor constructor = packageInstallerClass.getConstructor(Class.forName("android.content.pm.IPackageInstaller"), String.class, int.class);
                    packageInstaller = (PackageInstaller) constructor.newInstance(iPackageInstaller, "com.android.shell", 0);
                }
            }catch(Exception e) {
                return "Failed to create public PackageInstaller wrapper: " + e;
            }

            session = packageInstaller.openSession(sessionId);

            for(String apkPath : apkPaths) {
                File file = new File(apkPath);
                try (InputStream in = new FileInputStream(file);
                     OutputStream out = session.openWrite(file.getName(), 0, file.length())) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = in.read(buffer)) != -1) {
                        out.write(buffer, 0, count);
                    }
                    session.fsync(out);
                }
            }
            
            final CountDownLatch latch = new CountDownLatch(1);
            final Intent[] resultIntent = new Intent[1];

            IntentSender intentSender = IntentSenderUtils.newInstance(new IIntentSenderAdaptor() {
                @Override
                public void send(Intent intent) {
                    resultIntent[0] = intent;
                    latch.countDown();
                }
            });

            session.commit(intentSender);
            latch.await();
            if (resultIntent[0] == null) {
                return "Installation failed: no result from PackageInstaller.";
            }

            int status = resultIntent[0].getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (status == PackageInstaller.STATUS_SUCCESS) {
                return null;
            } else {
                String message = resultIntent[0].getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                return "Installation failed with status " + status + ": " + message;
            }
        }catch(Exception e) {
            if(session != null) {
                session.abandon();
            }
            return e.getMessage();
        }
    }

}
