package keystrokesmod.utility.profile;

import keystrokesmod.Raven;
import keystrokesmod.module.Module;
import keystrokesmod.module.setting.impl.ButtonSetting;
import keystrokesmod.module.setting.impl.GroupSetting;
import keystrokesmod.module.setting.impl.KeySetting;
import keystrokesmod.module.setting.impl.TextSetting;
import keystrokesmod.utility.Utils;

import java.awt.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

public class Manager extends Module {
    private final TextSetting createProfileName;
    private final ButtonSetting loadProfiles;
    private final ButtonSetting openFolder;
    private final ButtonSetting createProfile;
    private final Map<KeySetting, Profile> profileBinds = new IdentityHashMap<KeySetting, Profile>();

    public Manager() {
        super("Manager", category.configs);
        createProfileName = new TextSetting("Profile name", "", "Type a profile name...", 32, this::createProfile);
        createProfile = new ButtonSetting("Create profile", () -> {
            createProfile();
        });
        loadProfiles = new ButtonSetting("Load profiles", () -> {
            if (Utils.nullCheck() && Raven.profileManager != null) {
                Raven.profileManager.loadProfiles();
            }
        });
        openFolder = new ButtonSetting("Open folder", () -> {
            try {
                Desktop.getDesktop().open(Raven.profileManager.directory);
            }
            catch (IOException ex) {
                Raven.profileManager.directory.mkdirs();
                Utils.sendMessage("&cError locating folder, recreated.");
            }
        });
        rebuildProfileSettings();
        ignoreOnSave = true;
        canBeEnabled = false;
    }

    public void rebuildProfileSettings() {
        this.settings.clear();
        profileBinds.clear();
        this.registerSetting(createProfileName);
        this.registerSetting(createProfile);
        this.registerSetting(loadProfiles);
        this.registerSetting(openFolder);

        if (Raven.profileManager == null) {
            return;
        }

        List<Profile> sortedProfiles = new ArrayList<Profile>(Raven.profileManager.profiles);
        sortedProfiles.sort(Comparator.comparing(Profile::getName, String.CASE_INSENSITIVE_ORDER));
        for (Profile profile : sortedProfiles) {
            registerProfileSettings(profile);
        }
    }

    @Override
    public void guiUpdate() {
        for (Map.Entry<KeySetting, Profile> entry : profileBinds.entrySet()) {
            ProfileModule profileModule = entry.getValue().getModule();
            int bind = entry.getKey().getKey();
            if (profileModule.getKeycode() != bind) {
                profileModule.setBind(bind);
                profileModule.saved = false;
            }
        }
    }

    private void registerProfileSettings(Profile profile) {
        GroupSetting group = new GroupSetting(profile.getName());
        this.registerSetting(group);

        this.registerSetting(groupButton(group, "Load", () -> profile.getModule().toggle()));

        final TextSetting[] renameSetting = new TextSetting[1];
        renameSetting[0] = new TextSetting(group, "Name", profile.getName(), "Type a new profile name...", 32, () -> {
            String oldName = profile.getName();
            if (Raven.profileManager.renameProfile(profile, renameSetting[0].getText())) {
                Utils.sendMessage("&7Renamed profile: &b" + oldName + " &7to &b" + profile.getName());
                Raven.profileManager.refreshProfileModules();
            }
            else {
                renameSetting[0].setText(profile.getName());
            }
        });
        this.registerSetting(renameSetting[0]);

        KeySetting bind = new KeySetting(group, "Bind", profile.getModule().getKeycode());
        profileBinds.put(bind, profile);
        this.registerSetting(bind);

        this.registerSetting(groupButton(group, "Save", () -> {
            syncProfileBind(profile);
            Raven.profileManager.saveProfile(profile);
            profile.getModule().saved = true;
            Utils.sendMessage("&7Saved profile: &b" + profile.getName());
        }));
        this.registerSetting(groupButton(group, "Remove", () -> {
            String profileName = profile.getName();
            if (Raven.profileManager.deleteProfile(profileName)) {
                Utils.sendMessage("&7Removed profile: &b" + profileName);
            }
        }));
    }

    private ButtonSetting groupButton(GroupSetting group, String name, Runnable action) {
        ButtonSetting button = new ButtonSetting(name, action);
        button.group = group;
        return button;
    }

    private void syncProfileBind(Profile profile) {
        for (Map.Entry<KeySetting, Profile> entry : profileBinds.entrySet()) {
            if (entry.getValue() == profile) {
                profile.getModule().setBind(entry.getKey().getKey());
                return;
            }
        }
    }

    private void createProfile() {
        if (!Utils.nullCheck() || Raven.profileManager == null) {
            return;
        }

        Profile profile = Raven.profileManager.createProfile(createProfileName.getText(), 0);
        if (profile != null) {
            createProfileName.setText("");
            Utils.sendMessage("&7Created profile: &b" + profile.getName());
        }
    }
}
