package net.william278.husksync.migrator;

import com.zaxxer.hikari.HikariDataSource;
import net.william278.husksync.BukkitHuskSync;
import net.william278.husksync.config.Settings;
import net.william278.husksync.data.*;
import net.william278.husksync.player.User;
import net.william278.mpdbconverter.MPDBConverter;
import org.bukkit.Bukkit;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.regex.Pattern;

/**
 * A migrator for migrating MySQLPlayerDataBridge data to HuskSync {@link UserData}
 */
public class MpdbMigrator extends Migrator {

    private final MPDBConverter mpdbConverter;
    private String sourceHost;
    private int sourcePort;
    private String sourceUsername;
    private String sourcePassword;
    private String sourceDatabase;
    private String sourceInventoryTable;
    private String sourceEnderChestTable;
    private String sourceExperienceTable;
    private final String minecraftVersion;

    public MpdbMigrator(@NotNull BukkitHuskSync plugin, @NotNull Plugin mySqlPlayerDataBridge) {
        super(plugin);
        this.mpdbConverter = MPDBConverter.getInstance(mySqlPlayerDataBridge);
        this.sourceHost = plugin.getSettings().getStringValue(Settings.ConfigOption.DATABASE_HOST);
        this.sourcePort = plugin.getSettings().getIntegerValue(Settings.ConfigOption.DATABASE_PORT);
        this.sourceUsername = plugin.getSettings().getStringValue(Settings.ConfigOption.DATABASE_USERNAME);
        this.sourcePassword = plugin.getSettings().getStringValue(Settings.ConfigOption.DATABASE_PASSWORD);
        this.sourceDatabase = plugin.getSettings().getStringValue(Settings.ConfigOption.DATABASE_NAME);
        this.sourceInventoryTable = "mpdb_inventory";
        this.sourceEnderChestTable = "mpdb_enderchest";
        this.sourceExperienceTable = "mpdb_experience";
        this.minecraftVersion = plugin.getMinecraftVersion().toString();

    }

    @Override
    public CompletableFuture<Boolean> start() {
        plugin.getLoggingAdapter().log(Level.INFO, "Starting migration from MySQLPlayerDataBridge to HuskSync...");
        final long startTime = System.currentTimeMillis();
        return CompletableFuture.supplyAsync(() -> {
            // Wipe the existing database, preparing it for data import
            plugin.getLoggingAdapter().log(Level.INFO, "Preparing existing database (wiping)...");
            plugin.getDatabase().wipeDatabase().join();
            plugin.getLoggingAdapter().log(Level.INFO, "Successfully wiped user data database (took " + (System.currentTimeMillis() - startTime) + "ms)");

            // Create jdbc driver connection url
            final String jdbcUrl = "jdbc:mysql://" + sourceHost + ":" + sourcePort + "/" + sourceDatabase;

            // Create a new data source for the mpdb converter
            try (final HikariDataSource connectionPool = new HikariDataSource()) {
                plugin.getLoggingAdapter().log(Level.INFO, "Establishing connection to MySQLPlayerDataBridge database...");
                connectionPool.setJdbcUrl(jdbcUrl);
                connectionPool.setUsername(sourceUsername);
                connectionPool.setPassword(sourcePassword);
                connectionPool.setPoolName((getIdentifier() + "_migrator_pool").toUpperCase());

                plugin.getLoggingAdapter().log(Level.INFO, "Downloading raw data from the MySQLPlayerDataBridge database...");
                final List<MpdbData> dataToMigrate = new ArrayList<>();
                try (final Connection connection = connectionPool.getConnection()) {
                    try (final PreparedStatement statement = connection.prepareStatement("""
                            SELECT `%source_inventory_table%`.`player_uuid`, `%source_inventory_table%`.`player_name`, `inventory`, `armor`, `enderchest`, `exp_lvl`, `exp`, `total_exp`
                            FROM `%source_inventory_table%`
                                INNER JOIN `%source_ender_chest_table%`
                                    ON `%source_inventory_table%`.`player_uuid` = `%source_ender_chest_table%`.`player_uuid`
                                INNER JOIN `%source_xp_table%`
                                    ON `%source_inventory_table%`.`player_uuid` = `%source_xp_table%`.`player_uuid`;
                            """.replaceAll(Pattern.quote("%source_inventory_table%"), sourceInventoryTable)
                            .replaceAll(Pattern.quote("%source_ender_chest_table%"), sourceEnderChestTable)
                            .replaceAll(Pattern.quote("%source_xp_table%"), sourceExperienceTable))) {
                        try (final ResultSet resultSet = statement.executeQuery()) {
                            int playersMigrated = 0;
                            while (resultSet.next()) {
                                dataToMigrate.add(new MpdbData(
                                        new User(UUID.fromString(resultSet.getString("player_uuid")),
                                                resultSet.getString("player_name")),
                                        resultSet.getString("inventory"),
                                        resultSet.getString("armor"),
                                        resultSet.getString("enderchest"),
                                        resultSet.getInt("exp_lvl"),
                                        resultSet.getInt("exp"),
                                        resultSet.getInt("total_exp")
                                ));
                                playersMigrated++;
                                if (playersMigrated % 25 == 0) {
                                    plugin.getLoggingAdapter().log(Level.INFO, "Downloaded MySQLPlayerDataBridge data for " + playersMigrated + " players...");
                                }
                            }
                        }
                    }
                }
                plugin.getLoggingAdapter().log(Level.INFO, "Completed download of " + dataToMigrate.size() + " entries from the MySQLPlayerDataBridge database!");
                plugin.getLoggingAdapter().log(Level.INFO, "Converting raw MySQLPlayerDataBridge data to HuskSync user data...");
                dataToMigrate.forEach(data -> data.toUserData(mpdbConverter, minecraftVersion).thenAccept(convertedData ->
                        plugin.getDatabase().ensureUser(data.user()).thenRun(() ->
                                        plugin.getDatabase().setUserData(data.user(), convertedData, DataSaveCause.MPDB_MIGRATION))
                                .exceptionally(exception -> {
                                    plugin.getLoggingAdapter().log(Level.SEVERE, "Failed to migrate MySQLPlayerDataBridge data for " + data.user().username + ": " + exception.getMessage());
                                    return null;
                                })));
                plugin.getLoggingAdapter().log(Level.INFO, "Migration complete for " + dataToMigrate.size() + " users in " + ((System.currentTimeMillis() - startTime) / 1000) + " seconds!");
                return true;
            } catch (Exception e) {
                plugin.getLoggingAdapter().log(Level.SEVERE, "Error while migrating data: " + e.getMessage() + " - are your source database credentials correct?");
                return false;
            }
        });
    }

    @Override
    public void handleConfigurationCommand(@NotNull String[] args) {
        if (args.length == 2) {
            if (switch (args[0].toLowerCase()) {
                case "host" -> {
                    this.sourceHost = args[1];
                    yield true;
                }
                case "port" -> {
                    try {
                        this.sourcePort = Integer.parseInt(args[1]);
                        yield true;
                    } catch (NumberFormatException e) {
                        yield false;
                    }
                }
                case "username" -> {
                    this.sourceUsername = args[1];
                    yield true;
                }
                case "password" -> {
                    this.sourcePassword = args[1];
                    yield true;
                }
                case "database" -> {
                    this.sourceDatabase = args[1];
                    yield true;
                }
                case "inventory_table" -> {
                    this.sourceInventoryTable = args[1];
                    yield true;
                }
                case "ender_chest_table" -> {
                    this.sourceEnderChestTable = args[1];
                    yield true;
                }
                case "experience_table" -> {
                    this.sourceExperienceTable = args[1];
                    yield true;
                }
                default -> false;
            }) {
                plugin.getLoggingAdapter().log(Level.INFO, getHelpMenu());
                plugin.getLoggingAdapter().log(Level.INFO, "Successfully set " + args[0] + " to " +
                                                           obfuscateDataString(args[1]));
            } else {
                plugin.getLoggingAdapter().log(Level.INFO, "Invalid operation, could not set " + args[0] + " to " +
                                                           obfuscateDataString(args[1]) + " (is it a valid option?)");
            }
        } else {
            plugin.getLoggingAdapter().log(Level.INFO, getHelpMenu());
        }
    }

    @NotNull
    @Override
    public String getIdentifier() {
        return "mpdb";
    }

    @NotNull
    @Override
    public String getName() {
        return "MySQLPlayerDataBridge Migrator";
    }

    @NotNull
    @Override
    public String getHelpMenu() {
        return """
                === MySQLPlayerDataBridge Migration Wizard ==========
                This will migrate inventories, ender chests and XP
                from the MySQLPlayerDataBridge plugin to HuskSync.
                                
                To prevent excessive migration times, other non-vital
                data will not be transferred.
                                
                [!] Existing data in the database will be wiped. [!]
                                
                STEP 1] Please ensure no players are on any servers.
                                
                STEP 2] HuskSync will need to connect to the database
                used to hold the source MySQLPlayerDataBridge data.
                Please check these database parameters are OK:
                - host: %source_host%
                - port: %source_port%
                - username: %source_username%
                - password: %source_password%
                - database: %source_database%
                - inventory_table: %source_inventory_table%
                - ender_chest_table: %source_ender_chest_table%
                - experience_table: %source_xp_table%
                If any of these are not correct, please correct them
                using the command:
                "husksync migrate mpdb set <parameter> <value>"
                (e.g.: "husksync migrate mpdb set host 1.2.3.4")
                                
                STEP 3] HuskSync will migrate data into the database
                tables configures in the config.yml file of this
                server. Please make sure you're happy with this
                before proceeding.
                                
                STEP 4] To start the migration, please run:
                "husksync migrate mpdb start"
                """.replaceAll(Pattern.quote("%source_host%"), obfuscateDataString(sourceHost))
                .replaceAll(Pattern.quote("%source_port%"), Integer.toString(sourcePort))
                .replaceAll(Pattern.quote("%source_username%"), obfuscateDataString(sourceUsername))
                .replaceAll(Pattern.quote("%source_password%"), obfuscateDataString(sourcePassword))
                .replaceAll(Pattern.quote("%source_database%"), sourceDatabase)
                .replaceAll(Pattern.quote("%source_inventory_table%"), sourceInventoryTable)
                .replaceAll(Pattern.quote("%source_ender_chest_table%"), sourceEnderChestTable)
                .replaceAll(Pattern.quote("%source_xp_table%"), sourceExperienceTable);
    }

    /**
     * Represents data exported from the MySQLPlayerDataBridge source database
     *
     * @param user                 The user whose data is being migrated
     * @param serializedInventory  The serialized inventory data
     * @param serializedArmor      The serialized armor data
     * @param serializedEnderChest The serialized ender chest data
     * @param expLevel             The player's current XP level
     * @param expProgress          The player's current XP progress
     * @param totalExp             The player's total XP score
     */
    private record MpdbData(@NotNull User user, @NotNull String serializedInventory,
                            @NotNull String serializedArmor, @NotNull String serializedEnderChest,
                            int expLevel, float expProgress, int totalExp) {
        /**
         * Converts exported MySQLPlayerDataBridge data into HuskSync's {@link UserData} object format
         *
         * @param converter The {@link MPDBConverter} to use for converting to {@link ItemStack}s
         * @return A {@link CompletableFuture} that will resolve to the converted {@link UserData} object
         */
        @NotNull
        public CompletableFuture<UserData> toUserData(@NotNull MPDBConverter converter,
                                                      @NotNull String minecraftVersion) {
            return CompletableFuture.supplyAsync(() -> {
                // Combine inventory and armour
                final Inventory inventory = Bukkit.createInventory(null, InventoryType.PLAYER);
                inventory.setContents(converter.getItemStackFromSerializedData(serializedInventory));
                final ItemStack[] armor = converter.getItemStackFromSerializedData(serializedArmor).clone();
                for (int i = 36; i < 36 + armor.length; i++) {
                    inventory.setItem(i, armor[i - 36]);
                }

                // Create user data record
                return new UserData(new StatusData(20, 20, 0, 20, 10,
                        1, 0, totalExp, expLevel, expProgress, "SURVIVAL",
                        false),
                        new ItemData(BukkitSerializer.serializeItemStackArray(inventory.getContents()).join()),
                        new ItemData(BukkitSerializer.serializeItemStackArray(converter
                                .getItemStackFromSerializedData(serializedEnderChest)).join()),
                        new PotionEffectData(""), new ArrayList<>(),
                        new StatisticsData(new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>()),
                        new LocationData("world", UUID.randomUUID(), "NORMAL", 0, 0, 0,
                                0f, 0f),
                        new PersistentDataContainerData(new HashMap<>()),
                        minecraftVersion);
            });
        }
    }

}
