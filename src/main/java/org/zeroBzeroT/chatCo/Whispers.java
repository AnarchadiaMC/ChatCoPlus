package org.zeroBzeroT.chatCo;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;
import java.util.stream.Collectors;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import static org.zeroBzeroT.chatCo.Utils.componentFromLegacyText;
import static org.zeroBzeroT.chatCo.Utils.containsUnicode;
import static org.zeroBzeroT.chatCo.Utils.getDirectColorCode;
import static org.zeroBzeroT.chatCo.Utils.isVanished;
import static org.zeroBzeroT.chatCo.Utils.now;
import static org.zeroBzeroT.chatCo.Utils.parseFormattingTags;
import static org.zeroBzeroT.chatCo.Utils.stripColor;

import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

public class Whispers implements Listener {
    private final Main plugin;
    private final AntiSpam antiSpam;

    public Whispers(Main plugin, AntiSpam antiSpam) {
        this.plugin = plugin;
        this.antiSpam = antiSpam;
    }

    private static final Map<String, String> COLOR_PLACEHOLDER_MAP = new HashMap<>();

    static {
        // Initialize the color placeholder map
        for (String colorName : Utils.getNamedColors().keySet()) {
            COLOR_PLACEHOLDER_MAP.put("%" + colorName + "%", colorName);
        }
    }

    public TextComponent whisperFormat(Boolean send, final Player sender, final Player target) {
        Objects.requireNonNull(sender, "Sender cannot be null");
        Objects.requireNonNull(target, "Target cannot be null");

        String legacyMessage = send ? plugin.getConfig().getString("ChatCo.whisperFormat.send") : plugin.getConfig().getString("ChatCo.whisperFormat.receive");
        if (legacyMessage == null) {
            legacyMessage = send ? "&7To &f%RECEIVER%&7: " : "&7From &f%SENDER%&7: ";
        }

        // Replace color placeholders with actual color codes
        for (Map.Entry<String, String> entry : COLOR_PLACEHOLDER_MAP.entrySet()) {
            String colorName = entry.getValue();
            legacyMessage = legacyMessage.replace(entry.getKey(), getDirectColorCode(colorName));
        }

        String senderName = sender.getName();
        String targetName = target.getName();

        if (send) {
            legacyMessage = legacyMessage.replace("%SENDER%", senderName);
            legacyMessage = legacyMessage.replace("%RECEIVER%", targetName);
        } else {
            legacyMessage = legacyMessage.replace("%RECEIVER%", targetName);
            legacyMessage = legacyMessage.replace("%SENDER%", senderName);
        }

        return LegacyComponentSerializer.legacySection().deserialize(legacyMessage);
    }

    private void sendPrivateMessage(Player sender, Player receiver, String message) {
        // Double-check for blacklisted words and unicode as a safety measure
        // This prevents any bypasses that might occur in the command handling
        if (plugin.getConfig().getBoolean("ChatCo.blockUnicodeText", false) && containsUnicode(message)) {
            if (plugin.getConfig().getBoolean("ChatCo.debugUnicodeBlocking", false)) {
                plugin.getLogger().info("Blocked unicode whisper from " + sender.getName() + ": " + message);
            }
            return;
        }
        
        if (((Main) plugin).getBlacklistFilter().containsBlacklistedWord(message)) {
            if (plugin.getConfig().getBoolean("ChatCo.debugBlacklistBlocking", false)) {
                plugin.getLogger().info("Blocked blacklisted whisper from " + sender.getName() + ": " + message);
            }
            return;
        }
        
        boolean doNotSend = false;
        boolean isIgnoring = false;
        ChatPlayer target = ((Main) plugin).getChatPlayer(receiver);

        if (target != null && target.tellsDisabled) {
            doNotSend = true;
        }

        if (target != null && target.isIgnored(sender.getName())) {
            isIgnoring = true;
        }

        TextComponent senderMessage = whisperFormat(true, sender, receiver);
        TextComponent receiverMessage = whisperFormat(false, sender, receiver);

        // Apply color codes from the message and parse any formatting tags
        message = parseFormattingTags(message);
        TextComponent coloredMessage = LegacyComponentSerializer.legacySection().deserialize(message);
        
        receiverMessage = receiverMessage.append(coloredMessage);
        senderMessage = senderMessage.append(coloredMessage);

        boolean isBlackholed = BlackholeModule.isPlayerBlacklisted(sender);
        if (isBlackholed && !BlackholeModule.isPlayerHidden(sender)) {
            plugin.getLogger().log(Level.INFO, "Blocked message from {0}: {1}",
                    new Object[]{sender.getName(), stripColor(LegacyComponentSerializer.legacySection().serialize(senderMessage))});
        }

        sender.sendMessage(senderMessage);

        if (isIgnoring && plugin.getConfig().getBoolean("ChatCo.ignoreMessageEnabled", true)) {
            sender.sendMessage(componentFromLegacyText("&c" + receiver.getName() + " is ignoring you."));
        } else if (doNotSend && plugin.getConfig().getBoolean("ChatCo.chatDisabledMessageEnabled", true)) {
            sender.sendMessage(componentFromLegacyText("&c" + receiver.getName() + "'s chat is disabled."));
        } else if (!doNotSend && !isIgnoring && !isBlackholed) {
            receiver.sendMessage(receiverMessage);

            if (target != null)
                target.setLastMessenger(sender);
        }

        String logText = message;

        if (doNotSend || isIgnoring) {
            logText = "***WAS NOT SENT*** " + logText;
        }
        if (plugin.getConfig().getBoolean("ChatCo.whisperLog", false)) {
            whisperLog(logText, sender.getName());
        }
        if (plugin.getConfig().getBoolean("ChatCo.whisperMonitoring", false) && !BlackholeModule.isPlayerHidden(sender)) {
            plugin.getLogger().log(Level.INFO, "[WHISPER] {0} -> {1}: {2}", 
                new Object[]{sender.getName(), receiver.getName(), stripColor(logText)});
        }
    }

    public void whisperLog(final String text, final String sender) {
        try (FileWriter fwo = new FileWriter(Main.WhisperLog, true);
            BufferedWriter bwo = new BufferedWriter(fwo)) {
            bwo.write(now() + " " + sender + ": " + text);
            bwo.newLine();
        } catch (IOException ioexception) {
            plugin.getLogger().log(Level.SEVERE, "Failed to write to whisper log", ioexception);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerCommandPreprocess(PlayerCommandPreprocessEvent event) {
        Player sender = event.getPlayer();
        String fullCommand = event.getMessage();
        String[] args = fullCommand.split(" ");
        String cmdName = args[0].substring(1).toLowerCase();

        if (plugin.getConfig().getBoolean("ChatCo.lastCommand", true) && (cmdName.equals("l") || cmdName.equals("last"))) {
            if (args.length < 2) {
                sender.sendMessage(componentFromLegacyText("&eUsage: /l <message>"));
                event.setCancelled(true);
                return;
            }

            final Player target = ((Main) plugin).getChatPlayer(sender).getLastReceiver();

            if ((target == null && ((Main) plugin).getChatPlayer(sender).LastReceiver != null)
                    || isVanished(target)) {
                sender.sendMessage(componentFromLegacyText("&cThe last person you sent a private message to is offline."));
            } else if (target == null) {
                sender.sendMessage(componentFromLegacyText("&cYou have not initiated any private message in this session."));
            } else {
                String whisperMessage = Arrays.stream(args).skip(1).collect(Collectors.joining(" "));
                
                // Check for unicode characters
                if (plugin.getConfig().getBoolean("ChatCo.blockUnicodeText", false) && containsUnicode(whisperMessage)) {
                    if (plugin.getConfig().getBoolean("ChatCo.debugUnicodeBlocking", false)) {
                        plugin.getLogger().info("Blocked unicode whisper from " + sender.getName() + ": " + whisperMessage);
                    }
                    event.setCancelled(true);
                    return;
                }
                
                // Check for blacklisted words
                if (((Main) plugin).getBlacklistFilter().containsBlacklistedWord(whisperMessage)) {
                    if (plugin.getConfig().getBoolean("ChatCo.debugBlacklistBlocking", false)) {
                        plugin.getLogger().info("Blocked blacklisted whisper from " + sender.getName() + ": " + whisperMessage);
                    }
                    event.setCancelled(true);
                    return;
                }
                
                // Check for spam
                if (plugin.getConfig().getBoolean("AntiSpam.enabled", true)) {
                    AntiSpam.SpamCheckResult spamCheck = antiSpam.checkMessage(sender, whisperMessage);
                    if (spamCheck.isSpam()) {
                        event.setCancelled(true);
                        return;
                    }
                }
                
                sendPrivateMessage(sender, target, whisperMessage);
            }

            event.setCancelled(true);
        } else if (plugin.getConfig().getBoolean("ChatCo.replyCommands", true) && (cmdName.equals("r") || cmdName.equals("reply"))) {
            if (args.length < 2) {
                sender.sendMessage(componentFromLegacyText("&eUsage: /r <message>"));
                event.setCancelled(true);
                return;
            }

            final Player target = ((Main) plugin).getChatPlayer(sender).getLastMessenger();

            if ((target == null && ((Main) plugin).getChatPlayer(sender).LastMessenger != null)
                    || isVanished(target)) {
                sender.sendMessage(componentFromLegacyText("&cThe last person you received a private message from is offline."));
            } else if (target == null) {
                sender.sendMessage(componentFromLegacyText("&cYou have not received any private messages in this session."));
            } else {
                String whisperMessage = Arrays.stream(args).skip(1).collect(Collectors.joining(" "));
                
                // Check for unicode characters
                if (plugin.getConfig().getBoolean("ChatCo.blockUnicodeText", false) && containsUnicode(whisperMessage)) {
                    if (plugin.getConfig().getBoolean("ChatCo.debugUnicodeBlocking", false)) {
                        plugin.getLogger().info("Blocked unicode whisper from " + sender.getName() + ": " + whisperMessage);
                    }
                    event.setCancelled(true);
                    return;
                }
                
                // Check for blacklisted words
                if (((Main) plugin).getBlacklistFilter().containsBlacklistedWord(whisperMessage)) {
                    if (plugin.getConfig().getBoolean("ChatCo.debugBlacklistBlocking", false)) {
                        plugin.getLogger().info("Blocked blacklisted whisper from " + sender.getName() + ": " + whisperMessage);
                    }
                    event.setCancelled(true);
                    return;
                }
                
                // Check for spam
                if (plugin.getConfig().getBoolean("AntiSpam.enabled", true)) {
                    AntiSpam.SpamCheckResult spamCheck = antiSpam.checkMessage(sender, whisperMessage);
                    if (spamCheck.isSpam()) {
                        event.setCancelled(true);
                        return;
                    }
                }
                
                sendPrivateMessage(sender, target, whisperMessage);
            }

            event.setCancelled(true);
        } else if (cmdName.equals("tell") || cmdName.equals("msg") || cmdName.equals("t") || cmdName.equals("w") || cmdName.equals("whisper") || cmdName.equals("pm")) {
            if (args.length < 3) {
                sender.sendMessage(componentFromLegacyText("&eUsage: /w <player> <message>"));
                event.setCancelled(true);
                return;
            }

            final Player target = Bukkit.getPlayerExact(args[1]);

            if (target == null || isVanished(target)) {
                sender.sendMessage(componentFromLegacyText("&c" + args[1] + " is offline."));
                event.setCancelled(true);
                return;
            }

            String whisperMessage = Arrays.stream(args).skip(2).collect(Collectors.joining(" "));
            
            // Check for unicode characters
            if (plugin.getConfig().getBoolean("ChatCo.blockUnicodeText", false) && containsUnicode(whisperMessage)) {
                if (plugin.getConfig().getBoolean("ChatCo.debugUnicodeBlocking", false)) {
                    plugin.getLogger().info("Blocked unicode whisper from " + sender.getName() + ": " + whisperMessage);
                }
                event.setCancelled(true);
                return;
            }
            
            // Check for blacklisted words
            if (((Main) plugin).getBlacklistFilter().containsBlacklistedWord(whisperMessage)) {
                if (plugin.getConfig().getBoolean("ChatCo.debugBlacklistBlocking", false)) {
                    plugin.getLogger().info("Blocked blacklisted whisper from " + sender.getName() + ": " + whisperMessage);
                }
                event.setCancelled(true);
                return;
            }
            
            sendPrivateMessage(sender, target, whisperMessage);
            event.setCancelled(true);
            ((Main) plugin).getChatPlayer(sender).setLastReceiver(target);
        }
        // If not a whisper command, do not cancel
    }
}
