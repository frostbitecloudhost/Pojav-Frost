package net.kdt.pojavlaunch;

import static net.kdt.pojavlaunch.Tools.currentDisplayMetrics;
import static net.kdt.pojavlaunch.prefs.LauncherPreferences.PREF_ENABLE_LOG_OUTPUT;
import static net.kdt.pojavlaunch.prefs.LauncherPreferences.PREF_SUSTAINED_PERFORMANCE;
import static net.kdt.pojavlaunch.prefs.LauncherPreferences.PREF_USE_ALTERNATE_SURFACE;
import static net.kdt.pojavlaunch.prefs.LauncherPreferences.PREF_VIRTUAL_MOUSE_START;
import static org.lwjgl.glfw.CallbackBridge.sendKeyPress;
import static org.lwjgl.glfw.CallbackBridge.windowHeight;
import static org.lwjgl.glfw.CallbackBridge.windowWidth;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import com.kdt.LoggerView;
import com.movtery.ui.subassembly.customprofilepath.ProfilePathManager;

import net.kdt.pojavlaunch.customcontrols.ControlButtonMenuListener;
import net.kdt.pojavlaunch.customcontrols.ControlData;
import net.kdt.pojavlaunch.customcontrols.ControlDrawerData;
import net.kdt.pojavlaunch.customcontrols.ControlJoystickData;
import net.kdt.pojavlaunch.customcontrols.ControlLayout;
import net.kdt.pojavlaunch.customcontrols.EditorExitable;
import net.kdt.pojavlaunch.customcontrols.keyboard.LwjglCharSender;
import net.kdt.pojavlaunch.customcontrols.keyboard.TouchCharInput;
import net.kdt.pojavlaunch.customcontrols.mouse.GyroControl;
import net.kdt.pojavlaunch.customcontrols.mouse.Touchpad;
import net.kdt.pojavlaunch.lifecycle.ContextExecutor;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.services.GameService;
import net.kdt.pojavlaunch.utils.JREUtils;
import net.kdt.pojavlaunch.utils.MCOptionUtils;
import net.kdt.pojavlaunch.value.MinecraftAccount;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import org.lwjgl.glfw.CallbackBridge;

import java.io.File;
import java.io.IOException;
import java.util.Scanner;

public class MainActivity extends BaseActivity implements ControlButtonMenuListener, EditorExitable, ServiceConnection {
    public static volatile ClipboardManager GLOBAL_CLIPBOARD;
    public static final String INTENT_MINECRAFT_VERSION = "intent_version";

    volatile public static boolean isInputStackCall;

    public static TouchCharInput touchCharInput;
    private MinecraftGLSurface minecraftGLView;
    private static Touchpad touchpad;
    private LoggerView loggerView;
    private DrawerLayout drawerLayout;
    private ListView navDrawer;
    private View mDrawerPullButton;
    private GyroControl mGyroControl = null;
    public static ControlLayout mControlLayout;

    MinecraftProfile minecraftProfile;

    private ArrayAdapter<String> gameActionArrayAdapter;
    private AdapterView.OnItemClickListener gameActionClickListener;
    public ArrayAdapter<String> ingameControlsEditorArrayAdapter;
    public AdapterView.OnItemClickListener ingameControlsEditorListener;
    private GameService.LocalBinder mServiceBinder;

    private View startingView;
    private TextView startingTextView;
    private Handler handler;
    private File latestLogFile;
    private Thread logMonitorThread;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        minecraftProfile = LauncherProfiles.getCurrentProfile();
        MCOptionUtils.load(Tools.getGameDirPath(minecraftProfile).getAbsolutePath());

        Intent gameServiceIntent = new Intent(this, GameService.class);
        // Start the service a bit early
        ContextCompat.startForegroundService(this, gameServiceIntent);
        initLayout(R.layout.activity_basemain);
        CallbackBridge.addGrabListener(touchpad);
        CallbackBridge.addGrabListener(minecraftGLView);
        if(LauncherPreferences.PREF_ENABLE_GYRO) mGyroControl = new GyroControl(this);

        // Enabling this on TextureView results in a broken white result
        if(PREF_USE_ALTERNATE_SURFACE) getWindow().setBackgroundDrawable(null);
        else getWindow().setBackgroundDrawable(new ColorDrawable(Color.BLACK));

        // Set the sustained performance mode for available APIs
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
            getWindow().setSustainedPerformanceMode(PREF_SUSTAINED_PERFORMANCE);

        ingameControlsEditorArrayAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_1, getResources().getStringArray(R.array.menu_customcontrol));
        ingameControlsEditorListener = (parent, view, position, id) -> {
            switch(position) {
                case 0: mControlLayout.addControlButton(new ControlData("New")); break;
                case 1: mControlLayout.addDrawer(new ControlDrawerData()); break;
                case 2: mControlLayout.addJoystickButton(new ControlJoystickData()); break;
                case 3: mControlLayout.openLoadDialog(); break;
                case 4: mControlLayout.openSaveDialog(this); break;
                case 5: mControlLayout.openSetDefaultDialog(); break;
                case 6: mControlLayout.openExitDialog(this);
            }
        };

        // Recompute the gui scale when options are changed
        MCOptionUtils.MCOptionListener optionListener = MCOptionUtils::getMcScale;
        MCOptionUtils.addMCOptionListener(optionListener);
        mControlLayout.setModifiable(false);

        // Set the activity for the executor. Must do this here, or else Tools.showErrorRemote() may not
        // execute the correct method
        ContextExecutor.setActivity(this);
        //Now, attach to the service. The game will only start when this happens, to make sure that we know the right state.
        bindService(gameServiceIntent, this, 0);

        // Initialize and display the starting view
        initStartingView();
    }

    private void initStartingView() {
        handler = new Handler();
        LayoutInflater inflater = LayoutInflater.from(this);
        startingView = inflater.inflate(R.layout.starting_view, null);
        startingTextView = startingView.findViewById(R.id.starting_text);
        startingTextView.setText("Minecraft is starting...");
        addContentView(startingView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT));
        startingView.bringToFront();
        startingView.setBackgroundColor(Color.BLACK);

        monitorLogFile();
    }

    private void monitorLogFile() {
        latestLogFile = new File(Tools.DIR_GAME_HOME, "latestlog.txt");
        logMonitorThread = new Thread(() -> {
            try {
                Scanner scanner = new Scanner(latestLogFile);
                while (scanner.hasNextLine()) {
                    String line = scanner.nextLine();
                    if (line.contains("SoundEngineStarted")) {
                        handler.post(() -> startingView.setVisibility(View.GONE));
                        break;
                    }
                }
                scanner.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
        logMonitorThread.start();
    }

    protected void initLayout(int resId) {
        setContentView(resId);
        bindValues();
        mControlLayout.setMenuListener(this);

        mDrawerPullButton.setOnClickListener(v -> onClickedMenu());
        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED);

        try {
            File latestLogFile = new File(Tools.DIR_GAME_HOME, "latestlog.txt");
            if(!latestLogFile.exists() && !latestLogFile.createNewFile())
                throw new IOException("Failed to create a new log file");
            Logger.begin(latestLogFile.getAbsolutePath());
            // FIXME: is it safe for multi thread?
            GLOBAL_CLIPBOARD = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            touchCharInput.setCharacterSender(new LwjglCharSender());

            if(minecraftProfile.pojavRendererName != null) {
                Log.i("RdrDebug","__P_renderer="+minecraftProfile.pojavRendererName);
                Tools.LOCAL_RENDERER = minecraftProfile.pojavRendererName;
            }

            setTitle("Minecraft " + minecraftProfile.lastVersionId);

            // Minecraft 1.13+

            String version = getIntent().getStringExtra(INTENT_MINECRAFT_VERSION);
            version = version == null ? minecraftProfile.lastVersionId : version;

            JMinecraftVersionList.Version mVersionInfo = Tools.getVersionInfo(version);
            isInputStackCall = mVersionInfo.arguments != null;
            CallbackBridge.nativeSetUseInputStackQueue(isInputStackCall);

            Tools.getDisplayMetrics(this);
            windowWidth = Tools.getDisplayFriendlyRes(currentDisplayMetrics.widthPixels, 1f);
            windowHeight = Tools.getDisplayFriendlyRes(currentDisplayMetrics.heightPixels, 1f);
            CallbackBridge.windowWidth = windowWidth;
            CallbackBridge.windowHeight = windowHeight;

            if (mVersionInfo.arguments != null) {
                Tools.startAsyncTask(() -> {
                    try {
                        Tools.runMinecraft(mVersionInfo, this, minecraftProfile);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                });
            } else {
                Toast.makeText(this, "Version is not supported!", Toast.LENGTH_LONG).show();
            }

            if (PREF_ENABLE_LOG_OUTPUT) {
                findViewById(R.id.logger).setVisibility(View.VISIBLE);
                loggerView = findViewById(R.id.logger_view);
            } else {
                findViewById(R.id.logger).setVisibility(View.GONE);
            }

            touchpad = findViewById(R.id.touchpad);
            minecraftGLView = findViewById(R.id.minecraft);
            mDrawerPullButton = findViewById(R.id.drawer_pull_button);
            drawerLayout = findViewById(R.id.drawer_layout);
            navDrawer = findViewById(R.id.nav_drawer);
            mControlLayout = findViewById(R.id.control_layout);

            gameActionArrayAdapter = new ArrayAdapter<>(this,
                    android.R.layout.simple_list_item_1, getResources().getStringArray(R.array.game_actions));
            gameActionClickListener = (parent, view, position, id) -> {
                // handle game action clicks
            };
            navDrawer.setAdapter(gameActionArrayAdapter);
            navDrawer.setOnItemClickListener(gameActionClickListener);

            if (PREF_VIRTUAL_MOUSE_START) {
                // Start virtual mouse
            }

        } catch (Exception e) {
            e.printStackTrace();
            Tools.showErrorDialog(this, e.toString(), this::finish, false);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (logMonitorThread != null) {
            logMonitorThread.interrupt();
        }
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        mServiceBinder = (GameService.LocalBinder) service;
        mServiceBinder.startGame();
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        mServiceBinder = null;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        sendKeyPress(keyCode, 1);
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        sendKeyPress(keyCode, 0);
        return super.onKeyUp(keyCode, event);
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        Tools.getDisplayMetrics(this);
        windowWidth = Tools.getDisplayFriendlyRes(currentDisplayMetrics.widthPixels, 1f);
        windowHeight = Tools.getDisplayFriendlyRes(currentDisplayMetrics.heightPixels, 1f);
        CallbackBridge.windowWidth = windowWidth;
        CallbackBridge.windowHeight = windowHeight;
    }

    private void onClickedMenu() {
        if (drawerLayout.isDrawerOpen(navDrawer)) {
            drawerLayout.closeDrawer(navDrawer);
        } else {
            drawerLayout.openDrawer(navDrawer);
        }
    }

    @Override
    public void onAddControlButton() {
        mControlLayout.addControlButton(new ControlData("New Control Button"));
    }

    @Override
    public void onAddJoystickButton() {
        mControlLayout.addJoystickButton(new ControlJoystickData());
    }

    @Override
    public void onAddDrawer() {
        mControlLayout.addDrawer(new ControlDrawerData());
    }

    @Override
    public void onLoadControls() {
        mControlLayout.openLoadDialog();
    }

    @Override
    public void onSaveControls() {
        mControlLayout.openSaveDialog(this);
    }

    @Override
    public void onSetDefaultControls() {
        mControlLayout.openSetDefaultDialog();
    }

    @Override
    public void onExitEditor() {
        mControlLayout.openExitDialog(this);
    }
}
