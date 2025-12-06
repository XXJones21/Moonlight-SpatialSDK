package com.limelight.utils;

import android.app.Activity;
import android.content.Intent;
import android.widget.Toast;

// UI classes not available in Spatial SDK port
// import com.limelight.AppView;
// import com.limelight.Game;
// import com.limelight.ShortcutTrampoline;
import com.example.moonlight_spatialsdk.R;
import com.limelight.binding.PlatformBinding;
// ComputerManagerService not available in Spatial SDK port
// import com.limelight.computers.ComputerManagerService;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.HostHttpResponseException;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.nvstream.jni.MoonBridge;

import org.xmlpull.v1.XmlPullParserException;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.UnknownHostException;
import java.security.cert.CertificateEncodingException;

public class ServerHelper {
    public static final String CONNECTION_TEST_SERVER = "android.conntest.moonlight-stream.org";

    public static ComputerDetails.AddressTuple getCurrentAddressFromComputer(ComputerDetails computer) throws IOException {
        if (computer.activeAddress == null) {
            throw new IOException("No active address for "+computer.name);
        }
        return computer.activeAddress;
    }

    // UI intents not available in Spatial SDK - these methods are stubbed
    public static Intent createPcShortcutIntent(Activity parent, ComputerDetails computer) {
        // ShortcutTrampoline not available in Spatial SDK
        throw new UnsupportedOperationException("Shortcuts not supported in Spatial SDK port");
    }

    public static Intent createAppShortcutIntent(Activity parent, ComputerDetails computer, NvApp app) {
        // ShortcutTrampoline not available in Spatial SDK
        throw new UnsupportedOperationException("Shortcuts not supported in Spatial SDK port");
    }

    public static Intent createStartIntent(Activity parent, NvApp app, ComputerDetails computer,
                                           Object managerBinder) {
        // Game activity not available in Spatial SDK - use MoonlightConnectionManager instead
        throw new UnsupportedOperationException("Game activity not available in Spatial SDK - use MoonlightConnectionManager");
    }

    public static void doStart(Activity parent, NvApp app, ComputerDetails computer,
                               Object managerBinder) {
        if (computer.state == ComputerDetails.State.OFFLINE || computer.activeAddress == null) {
            Toast.makeText(parent, "PC is offline", Toast.LENGTH_SHORT).show();
            return;
        }
        parent.startActivity(createStartIntent(parent, app, computer, managerBinder));
    }

    public static void doNetworkTest(final Activity parent) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                SpinnerDialog spinnerDialog = SpinnerDialog.displayDialog(parent,
                        "Network Test",
                        "Testing connectivity...",
                        false);

                int ret = MoonBridge.testClientConnectivity(CONNECTION_TEST_SERVER, 443, MoonBridge.ML_PORT_FLAG_ALL);
                spinnerDialog.dismiss();

                String dialogSummary;
                if (ret == MoonBridge.ML_TEST_RESULT_INCONCLUSIVE) {
                    dialogSummary = "Network test inconclusive";
                }
                else if (ret == 0) {
                    dialogSummary = "Network test successful";
                }
                else {
                    dialogSummary = "Network test failed";
                    dialogSummary += MoonBridge.stringifyPortFlags(ret, "\n");
                }

                Dialog.displayDialog(parent,
                        "Network Test Complete",
                        dialogSummary,
                        false);
            }
        }).start();
    }

    public static void doQuit(final Activity parent,
                              final ComputerDetails computer,
                              final NvApp app,
                              final Object managerBinder,
                              final Runnable onComplete) {
        Toast.makeText(parent, "Quitting " + app.getAppName() + "...", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                NvHTTP httpConn;
                String message;
                try {
                    String uniqueId = null;
                    if (managerBinder != null) {
                        try {
                            uniqueId = (String) managerBinder.getClass().getMethod("getUniqueId").invoke(managerBinder);
                        } catch (Exception e) {
                            // Fallback if reflection fails
                        }
                    }
                    if (uniqueId == null) {
                        uniqueId = android.provider.Settings.Secure.getString(parent.getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
                    }
                    httpConn = new NvHTTP(ServerHelper.getCurrentAddressFromComputer(computer), computer.httpsPort,
                            uniqueId, computer.serverCert, PlatformBinding.getCryptoProvider(parent));
                    if (httpConn.quitApp()) {
                        message = "Successfully quit " + app.getAppName();
                    } else {
                        message = "Failed to quit " + app.getAppName();
                    }
                } catch (HostHttpResponseException e) {
                    if (e.getErrorCode() == 599) {
                        message = "This session wasn't started by this device," +
                                " so it cannot be quit. End streaming on the original " +
                                "device or the PC itself. (Error code: "+e.getErrorCode()+")";
                    }
                    else {
                        message = e.getMessage();
                    }
                } catch (UnknownHostException e) {
                    message = "Unknown host";
                } catch (FileNotFoundException e) {
                    message = "404 Not Found";
                } catch (IOException | XmlPullParserException e) {
                    message = e.getMessage();
                    e.printStackTrace();
                } finally {
                    if (onComplete != null) {
                        onComplete.run();
                    }
                }

                final String toastMessage = message;
                parent.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(parent, toastMessage, Toast.LENGTH_LONG).show();
                    }
                });
            }
        }).start();
    }
}
