package com.ethercamp.harmony.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.filter.LevelFilter;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import com.ethercamp.harmony.api.EthereumApiImpl;
import com.ethercamp.harmony.dto.*;
import com.sun.management.OperatingSystemMXBean;
import lombok.extern.slf4j.Slf4j;
import org.ethereum.core.Block;
import org.ethereum.core.TransactionReceipt;
import org.ethereum.facade.Ethereum;
import org.ethereum.listener.EthereumListenerAdapter;
import org.slf4j.LoggerFactory;
import org.spongycastle.util.encoders.Hex;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.text.DecimalFormat;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Created by Stan Reshetnyk on 11.07.16.
 */
@Slf4j(topic = "harmony")
@Service
public class MachineInfoService {

    public static final int KEEP_LOG_ENTRIES = 1000;
    private static final int BLOCK_COUNT_FOR_HASH_RATE = 100;
    private static final int KEEP_BLOCKS_FOR_CLIENT = 50;

    @Autowired
    private Environment env;

    @Autowired
    private ClientMessageService clientMessageService;

    @Autowired
    private Ethereum ethereum;

    @Autowired
    private EthereumApiImpl ethereumApi;


    /**
     * Concurrent queue of last blocks.
     * Ethereum adds items when available.
     * Service reads items with interval.
     */
    private final Queue<Block> lastBlocksForHashRate = new ConcurrentLinkedQueue();

    private final Queue<BlockInfo> lastBlocksForClient = new ConcurrentLinkedQueue();

    private final AtomicReference<MachineInfoDTO> machineInfo = new AtomicReference<>(new MachineInfoDTO(0, 0l, 0l, 0l));

    private final AtomicReference<BlockchainInfoDTO> blockchainInfo =
            new AtomicReference<>(new BlockchainInfoDTO(0l, 0l, 0, 0l, 0l, 0l));

    private final AtomicReference<NetworkInfoDTO> networkInfo = new AtomicReference<>();

    private final AtomicReference<InitialInfoDTO> initialInfo = new AtomicReference<>();

    private final Queue<String> lastLogs = new ConcurrentLinkedQueue();

    public InitialInfoDTO getInitialInfo() {
        return initialInfo.get();
    }


    @PostConstruct
    private void postConstruct() {
        /**
         * - gather blocks to calculate hash rate;
         * - gather blocks to keep for client block tree;
         * - notify client on new block.
         */
        ethereum.addListener(new EthereumListenerAdapter() {
            @Override
            public void onBlock(Block block, List<TransactionReceipt> receipts) {
                lastBlocksForHashRate.add(block);

                if (lastBlocksForHashRate.size() > BLOCK_COUNT_FOR_HASH_RATE) {
                    lastBlocksForHashRate.poll();
                }
                if (lastBlocksForClient.size() > KEEP_BLOCKS_FOR_CLIENT) {
                    lastBlocksForClient.poll();
                }

                BlockInfo blockInfo = new BlockInfo(
                        block.getNumber(),
                        Hex.toHexString(block.getHash()),
                        Hex.toHexString(block.getParentHash()),
                        block.getDifficultyBI().longValue()
                );
                lastBlocksForClient.add(blockInfo);
                clientMessageService.sendToTopic("/topic/newBlockInfo", blockInfo);
            }
        });

        final Optional<String> blockHash = Optional.ofNullable(ethereumApi.getBlock(0l))
                .map(block -> Hex.toHexString(block.getHash()));
        final String networkName = blockHash
                .map(hash -> BlockchainConsts.GENESIS_BLOCK_HASH_MAP.getOrDefault(hash, "Unknown network"))
                .orElse("Undefined blockchain");

        initialInfo.set(new InitialInfoDTO(
                env.getProperty("ethereumJ.version"),
                env.getProperty("app.version"),
                networkName,
                blockHash.orElse(null),
                System.currentTimeMillis(),
                ethereumApi.getNodeId()));

        createLogAppenderForMessaging();
    }

    public MachineInfoDTO getMachineInfo() {
        return machineInfo.get();
    }

    public Queue<String> getSystemLogs() {
        return lastLogs;
    }

    public Queue<BlockInfo> getBlocks() {
        return lastBlocksForClient;
    }

    @Scheduled(fixedRate = 5000)
    private void doUpdateMachineInfoStatus() {

        final OperatingSystemMXBean bean = (OperatingSystemMXBean) ManagementFactory
                .getOperatingSystemMXBean();

        machineInfo.set(new MachineInfoDTO(
                ((Double) (bean.getSystemCpuLoad() * 100)).intValue(),
                bean.getFreePhysicalMemorySize(),
                bean.getTotalPhysicalMemorySize(),
                getFreeDiskSpace()
        ));

        clientMessageService.sendToTopic("/topic/machineInfo", machineInfo.get());
    }

    @Scheduled(fixedRate = 2000)
    private void doUpdateBlockchainStatus() {

        final Block bestBlock = ethereum.getBlockchain().getBestBlock();

        blockchainInfo.set(
                new BlockchainInfoDTO(
                        bestBlock.getNumber(),
                        bestBlock.getTimestamp(),
                        bestBlock.getTransactionsList().size(),
                        bestBlock.getDifficultyBI().longValue(),
                        0l,
                        calculateHashRate()
                )
        );

        clientMessageService.sendToTopic("/topic/blockchainInfo", blockchainInfo.get());
    }

    @Scheduled(fixedRate = 2000)
    private void doUpdateNetworkInfo() {

        networkInfo.set(
                new NetworkInfoDTO(
                        ethereumApi.getActivePeersCount(),
                        ethereumApi.getSyncStatus().toString(),
                        ethereumApi.getEthPort(),
                        true
                )
        );

        clientMessageService.sendToTopic("/topic/networkInfo", blockchainInfo.get());
    }

    private long calculateHashRate() {
        final List<Block> blocks = Arrays.asList(lastBlocksForHashRate.toArray(new Block[0]));

        if (blocks.isEmpty()) {
            return 0;
        }

        Block bestBlock = blocks.get(blocks.size() - 1);
        long difficulty = bestBlock.getDifficultyBI().longValue();

        long sumTimestamps = blocks.stream().mapToLong(b -> b.getTimestamp()).sum();
        return difficulty / (sumTimestamps / blocks.size() / 1000);
    }

    /**
     * Get free space of disk where project located.
     * Verified on multi disk Windows.
     * Not tested against sym links
     */
    private long getFreeDiskSpace() {
        final File currentDir = new File(".");
        for (Path root : FileSystems.getDefault().getRootDirectories()) {
            log.debug(root.toAbsolutePath() + " vs current " + currentDir.getAbsolutePath());
            try {
                final FileStore store = Files.getFileStore(root);

                final boolean isCurrentDirBelongsToRoot = Paths.get(currentDir.getAbsolutePath()).startsWith(root.toAbsolutePath());
                if (isCurrentDirBelongsToRoot) {
                    final long usableSpace = store.getUsableSpace();
                    log.debug("Disk available:" + readableFileSize(usableSpace)
                            + ", total:" + readableFileSize(store.getTotalSpace()));
                    return usableSpace;
                }
            } catch (IOException e) {
                log.error("Problem querying space: " + e.toString());
            }
        }
        return 0;
    }

    // for better logs
    private String readableFileSize(long size) {
        if (size <= 0) return "0";
        final String[] units = new String[]{"B", "kB", "MB", "GB", "TB"};
        int digitGroups = (int) (Math.log10(size) / Math.log10(1024));
        return new DecimalFormat("#,##0.#").format(size / Math.pow(1024, digitGroups)) + " " + units[digitGroups];
    }

    /**
     * Create log appender, which will subscribe to loggers, we are interested in.
     * Appender will send logs to messaging topic then (for delivering to client side).
     */
    private void createLogAppenderForMessaging() {
        final LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();

        final PatternLayout patternLayout = new PatternLayout();
        patternLayout.setPattern("%d %-5level [%thread] %logger{35} - %msg%n");
        patternLayout.setContext(context);
        patternLayout.start();

        final UnsynchronizedAppenderBase messagingAppender = new UnsynchronizedAppenderBase() {
            @Override
            protected void append(Object eventObject) {
                LoggingEvent event = (LoggingEvent) eventObject;
                String message = patternLayout.doLayout(event);
                lastLogs.add(message);
                if (lastLogs.size() > KEEP_LOG_ENTRIES) {
                    lastLogs.poll();
                }
                clientMessageService.sendToTopic("/topic/systemLog", message);
            }
        };

        // No effect of this
        final LevelFilter filter = new LevelFilter();
        filter.setLevel(Level.INFO);
        messagingAppender.addFilter(filter);
        messagingAppender.setName("ClientMessagingAppender");

        messagingAppender.start();

        // Attach appender to specific loggers
        Arrays.asList("blockchain", "sync", "facade", "net", "general")
                .stream()
                .forEach(l -> {
                    Logger logger = context.getLogger(l);
                    logger.setLevel(Level.INFO);
                    logger.addAppender(messagingAppender);
                });

        // way to subscribe to all loggers existing at the moment
//        context.getLoggerList().stream()
//                .forEach(l -> l.addAppender(messagingAppender));
    }

    public static class BlockchainConsts {

        public static final Map<String, String> GENESIS_BLOCK_HASH_MAP = new HashMap<>();

        static {
            GENESIS_BLOCK_HASH_MAP.put("d4e56740f876aef8c010b86a40d5f56745a118d0906a34e69aec8c0db1cb8fa3", "Live ETH");
            GENESIS_BLOCK_HASH_MAP.put("0cd786a2425d16f152c658316c423e6ce1181e15c3295826d7c9904cba9ce303", "Morden ETH");
            GENESIS_BLOCK_HASH_MAP.put("0cd786a2425d16f152c658316c423e6ce1181e15c3295826d7c9904cba9ce303", "Test ETH");
        }
    }
}
