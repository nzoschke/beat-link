package org.deepsymmetry.beatlink;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicInteger;

import org.deepsymmetry.beatlink.dbserver.BinaryField;
import org.deepsymmetry.beatlink.dbserver.Client;
import org.deepsymmetry.beatlink.dbserver.Message;
import org.deepsymmetry.beatlink.dbserver.NumberField;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;

/**
 * Watches for new tracks to be loaded on players, and queries the
 * appropriate player for the metadata information when that happens.<p>
 *
 * @author James Elliott
 */
public class MetadataFinder {

    private static final Logger logger = LoggerFactory.getLogger(MetadataFinder.class.getName());

    /**
     * Given a status update from a CDJ, find the metadata for the track that it has loaded, if any.
     *
     * @param status the CDJ status update that will be used to determine the loaded track and ask the appropriate
     *               player for metadata about it
     * @return the metadata that was obtained, if any
     */
    public static TrackMetadata requestMetadataFrom(CdjStatus status) {
        if (status.getTrackSourceSlot() == CdjStatus.TrackSourceSlot.NO_TRACK || status.getRekordboxId() == 0) {
            return null;
        }
        return requestMetadataFrom(status.getTrackSourcePlayer(), status.getTrackSourceSlot(), status.getRekordboxId());
    }

    /**
     * The delimiter which separates individual messages in the TCP stream.
     */
    private static byte[] messageSeparator =  {(byte)0x11, (byte)0x87, (byte)0x23, (byte)0x49, (byte)0xae, (byte)0x11};

    /**
     * The payload of a packet which determines how many tracks are available in a given media slot.
     */
    private static byte[] trackCountRequestPacket = {
            0x10, 0x10, 0x04, 0x0f, 0x02, 0x14, 0x00, 0x00,
            0x00, 0x0c, 0x06, 0x06, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x11, 0 /* player */,
            0x02, 0 /* slot */, 0x01, 0x11, 0x00, 0x00, 0x00, 0x00
    };

    /**
     * Stores a 4-byte id value in the proper byte order into a byte buffer that is being used to build up
     * a metadata query.
     *
     * @param buffer the buffer in which the query is being created.
     * @param offset the index of the first byte where the id is to be stored.
     * @param id the 4-byte id that needs to be stored in the query buffer.
     */
    private static void setIdBytes(byte[] buffer, int offset, int id) {
        buffer[offset] = (byte)(id >> 24);
        buffer[offset + 1] = (byte)(id >> 16);
        buffer[offset + 2] = (byte)(id >>8);
        buffer[offset + 3] = (byte)id;
    }

    /**
     * Formats a query packet to be sent to the player.
     *
     * @param messageId the sequence number of the message; should start with 1 and be incremented with each message.
     * @param payload the bytes which should follow teh separator and sequence number.
     * @return the formatted query packet.
     */
    private static byte[] buildPacket(int messageId, byte[] payload) {
        byte[] result = new byte[payload.length + messageSeparator.length + 4];
        System.arraycopy(messageSeparator, 0, result, 0, messageSeparator.length);
        setIdBytes(result, messageSeparator.length, messageId);
        System.arraycopy(payload, 0, result, messageSeparator.length + 4, payload.length);
        return result;
    }

    /**
     * Receive some bytes from the player we are requesting metadata from.
     *
     * @param is the input stream associated with the player metadata socket.
     * @return the bytes read.
     *
     * @throws IOException if there is a problem reading the response
     */
    private static byte[] receiveBytes(InputStream is) throws IOException {
        byte[] buffer = new byte[8192];
        int len = (is.read(buffer));
        if (len < 1) {
            throw new IOException("receiveBytes read " + len + " bytes.");
        }
        return Arrays.copyOf(buffer, len);
    }

    /**
     * Receive an expected number of bytes from the player, logging a warning if we get a different number of them.
     *
     * @param is the input stream associated with the player metadata socket.
     * @param size the number of bytes we expect to receive.
     * @param description the type of response being processed, for use in the warning message.
     * @return the bytes read.
     *
     * @throws IOException if there is a problem reading the response.
     */
    private static byte[] readResponseWithExpectedSize(InputStream is, int size, String description) throws IOException {
        byte[] result = receiveBytes(is);
        if (result.length != size) {
            logger.warn("Expected " + size + " bytes while reading " + description + " response, received " + result.length);
        }
        return result;
    }



    /**
     * Finds a valid  player number that is currently visible but which is different from the one specified, so it can
     * be used as the source player for a query being sent to the specified one. If the virtual CDJ is running on an
     * acceptable player number (which must be 1-4 to request metadata from an actual CDJ, but can be anything if we
     * are talking to rekordbox), uses that, since it will always be safe. Otherwise, picks an existing player number,
     * but this will cause the query to fail if that player has mounted media from the player we are querying.
     *
     * @param player the player to which a metadata query is being sent
     * @param slot the media slot from which the track of interest was loaded
     *
     * @return some other currently active player number
     *
     * @throws IllegalStateException if there is no other player number available to use.
     */
    private static int chooseAskingPlayerNumber(int player, CdjStatus.TrackSourceSlot slot) {
        final int fakeDevice = VirtualCdj.getDeviceNumber();
        if (slot == CdjStatus.TrackSourceSlot.COLLECTION || (fakeDevice >= 1 && fakeDevice <= 4)) {
            return fakeDevice;
        }

        for (DeviceAnnouncement candidate : DeviceFinder.currentDevices()) {
            final int realDevice = candidate.getNumber();
            if (realDevice != player && realDevice >= 1 && realDevice <= 4) {
                final DeviceUpdate lastUpdate =  VirtualCdj.getLatestStatusFor(realDevice);
                if (lastUpdate != null && lastUpdate instanceof CdjStatus &&
                        ((CdjStatus)lastUpdate).getTrackSourcePlayer() != player) {
                    return candidate.getNumber();
                }
            }
        }
        throw new IllegalStateException("No player number available to query player " + player +
                ". If they are on the network, they must be using Link to play a track from that player, " +
                "so we can't use their ID.");
    }

    /**
     * Request metadata for a specific track ID, given a connection to a player that has already been set up.
     * Separated into its own method so it could be used multiple times on the same connection when gathering
     * all track metadata.
     *
     * @param rekordboxId the track of interest
     * @param slot identifies the media slot we are querying
     * @param client the dbserver client that is communicating with the appropriate player
     *
     * @return the retrieved metadata, or {@code null} if there is no such track
     *
     * @throws IOException if there is a communication problem
     */
    private static TrackMetadata getTrackMetadata(int rekordboxId, CdjStatus.TrackSourceSlot slot, Client client)
            throws IOException {

        // Send the metadata menu request
        Message response = client.menuRequest(Message.KnownType.METADATA_REQ, Message.MenuIdentifier.MAIN_MENU, slot,
                new NumberField(rekordboxId));
        final long count = response.getMenuResultsCount();
        if (count == Message.NO_MENU_RESULTS_AVAILABLE) {
            return null;
        }

        // Gather all the metadata menu items
        final List<Message> items = client.renderMenuItems(Message.MenuIdentifier.MAIN_MENU, slot, response);
        TrackMetadata result = new TrackMetadata(items);
        if (result.getArtworkId() != 0) {
            result = result.withArtwork(requestArtwork(result.getArtworkId(), slot, client));
        }
        return result;
    }

    /**
     * Ask the specified player for metadata about the track in the specified slot with the specified rekordbox ID.
     *
     * @param player the player number whose track is of interest
     * @param slot the slot in which the track can be found
     * @param rekordboxId the track of interest
     * @return the metadata, if any
     */
    public static TrackMetadata requestMetadataFrom(int player, CdjStatus.TrackSourceSlot slot, int rekordboxId) {
        final DeviceAnnouncement deviceAnnouncement = DeviceFinder.getLatestAnnouncementFrom(player);
        final int dbServerPort = getPlayerDBServerPort(player);
        if (deviceAnnouncement == null || dbServerPort < 0) {
            return null;  // If the device isn't known, or did not provide a database server, we can't get metadata.
        }

        final byte posingAsPlayerNumber = (byte) chooseAskingPlayerNumber(player, slot);

        Socket socket = null;
        try {
            InetSocketAddress address = new InetSocketAddress(deviceAnnouncement.getAddress(), dbServerPort);
            socket = new Socket();
            socket.connect(address, 5000);
            Client client = new Client(socket, player, posingAsPlayerNumber);

            return getTrackMetadata(rekordboxId, slot, client);
        } catch (Exception e) {
            logger.warn("Problem requesting metadata", e);
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException e) {
                    logger.warn("Problem closing metadata request socket", e);
                }
            }
        }
        return null;
    }

    /**
     * Request the artwork associated with a track whose metadata is being retrieved.
     *
     * @param artworkId identifies the album art to retrieve
     * @param slot the slot identifier from which the track was loaded
     * @param client the dbserver client that is communicating with the appropriate player
     *
     * @return the track's artwork, or null if none is available
     *
     * @throws IOException if there is a problem communicating with the player
     */
    private static BufferedImage requestArtwork(int artworkId, CdjStatus.TrackSourceSlot slot, Client client)
            throws IOException {

        // Send the artwork request
        Message response = client.simpleRequest(Message.KnownType.ALBUM_ART_REQ, Message.KnownType.ALBUM_ART,
                client.buildRMS1(Message.MenuIdentifier.DATA, slot), new NumberField((long)artworkId));

        // Create an image from the response bytes
        ByteBuffer imageBuffer = ((BinaryField)response.arguments.get(3)).getValue();
        byte[] imageBytes = new byte[imageBuffer.remaining()];
        return ImageIO.read(new ByteArrayInputStream(imageBytes));
    }

    /**
     * The largest gap between rekordbox IDs we will accept while trying to scan for all the metadata in a media
     * slot. If we try this many times and fail to get a valid response, we will give up our scan.
     */
    public static final int MAX_GAP = 128;

    /**
     * Ask the specified player for all the tracks in the specified slot.
     *
     * @param player the player number whose database is of interest
     * @param slot the slot in which the tracks are to be examined
     * @return the metadata for all tracks in that slot
     *
     * @throws IOException if there is a problem communicating with the CDJs
     * @throws IllegalStateException if there is no media in the specified slot, or the specified player is not found
     */
    public static Map<Integer,TrackMetadata> requestAllMetadataFrom(int player, CdjStatus.TrackSourceSlot slot) throws IOException {
        final DeviceAnnouncement deviceAnnouncement = DeviceFinder.getLatestAnnouncementFrom(player);
        final int dbServerPort = getPlayerDBServerPort(player);
        if (deviceAnnouncement == null || dbServerPort < 0) {
            throw new IllegalStateException("Unable to request track count from speficied player.");
        }

        final byte posingAsPlayerNumber = (byte) chooseAskingPlayerNumber(player, slot);

        Socket socket = null;
        try {
            socket = new Socket(deviceAnnouncement.getAddress(), dbServerPort);
            InputStream is = socket.getInputStream();
            OutputStream os = socket.getOutputStream();
            socket.setSoTimeout(3000);
            Client client = new Client(socket, player, posingAsPlayerNumber);

            Map<Integer,TrackMetadata> result = new HashMap<Integer, TrackMetadata>();
            AtomicInteger messageID = new AtomicInteger();

            // Send the packet requesting the track count
            byte[] payload = new byte[trackCountRequestPacket.length];
            System.arraycopy(trackCountRequestPacket, 0, payload, 0, trackCountRequestPacket.length);
            payload[23] = posingAsPlayerNumber;
            payload[25] = slot.protocolValue;
            os.write(buildPacket(messageID.incrementAndGet(), payload));
            byte[] response = readResponseWithExpectedSize(is, 42, "track count message");
            if (response.length < 42) {
                throw new IllegalStateException("No media present in the specified player slot");
            }
            final int totalTracks = (int)Util.bytesToNumber(response, 40, 2);
            logger.info("Trying to load " + totalTracks + " tracks.");

            int maxGap = 0;
            int gap = 0;
            int currentId = 1;

            while (result.size() < totalTracks) {
                TrackMetadata found = getTrackMetadata(currentId, slot, client);
                if (found != null) {
                    gap = 0;
                    result.put(currentId, found);
                } else {
                    gap += 1;
                    if (gap > MAX_GAP) {
                        logger.warn("Failed " + gap + " times in a row requesting track metadata, giving up.");
                        return result;
                    }
                    if (gap > maxGap) {
                        maxGap = gap;
                    }
                }
                currentId += 1;
            }

            logger.info("Finished loading " + totalTracks + " tracks; maximum gap: " + maxGap);
            return result;
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException e) {
                    logger.warn("Problem closing metadata request socket", e);
                }
            }
        }
    }

    /**
     * Keeps track of the current metadata known for each player.
     */
    private static final Map<Integer, TrackMetadata> metadata = new HashMap<Integer, TrackMetadata>();

    /**
     * Keeps track of the previous update from each player that we retrieved metadata about, to check whether a new
     * track has been loaded.
     */
    private static final Map<InetAddress, CdjStatus> lastUpdates = new HashMap<InetAddress, CdjStatus>();

    /**
     * A queue used to hold CDJ status updates we receive from the {@link VirtualCdj} so we can process them on a
     * lower priority thread, and not hold up delivery to more time-sensitive listeners.
     */
    private static LinkedBlockingDeque<CdjStatus> pendingUpdates = new LinkedBlockingDeque<CdjStatus>(100);

    /**
     * Our update listener just puts appropriate device updates on our queue, so we can process them on a lower
     * priority thread, and not hold up delivery to more time-sensitive listeners.
     */
    private static DeviceUpdateListener updateListener = new DeviceUpdateListener() {
        @Override
        public void received(DeviceUpdate update) {
            //logger.log(Level.INFO, "Received: " + update);
            if (update instanceof CdjStatus) {
                //logger.log(Level.INFO, "Queueing");
                if (!pendingUpdates.offerLast((CdjStatus)update)) {
                    logger.warn("Discarding CDJ update because our queue is backed up.");
                }
            }
        }
    };

    /**
     * Keeps track of the database server ports of all the players we have seen on the network.
     */
    private static final Map<Integer, Integer> dbServerPorts = new HashMap<Integer, Integer>();

    /**
     * Look up the database server port reported by a given player.
     *
     * @param player the player number of interest.
     *
     * @return the port number on which its database server is running, or -1 if unknown.
     */
    public static synchronized int getPlayerDBServerPort(int player) {
        Integer result = dbServerPorts.get(player);
        if (result == null) {
            return -1;
        }
        return result;
    }

    /**
     * Record the database server port reported by a player.
     *
     * @param player the player number whose server port has been determined.
     * @param port the port number on which the player's database server is running.
     */
    private static synchronized void setPlayerDBServerPort(int player, int port) {
        dbServerPorts.put(player, port);
    }

    /**
     * The port on which we can request information about a player, including the port on which its database server
     * is running.
     */
    private static final int DB_SERVER_QUERY_PORT = 12523;

    private static final byte[] DB_SERVER_QUERY_PACKET = {
            0x00, 0x00, 0x00, 0x0f,
            0x52, 0x65, 0x6d, 0x6f, 0x74, 0x65, 0x44, 0x42, 0x53, 0x65, 0x72, 0x76, 0x65, 0x72,  // RemoteDBServer
            0x00
    };

    /**
     * Query a player to determine the port on which its database server is running.
     *
     * @param announcement the device announcement with which we detected a new player on the network.
     */
    private static void requestPlayerDBServerPort(DeviceAnnouncement announcement) {
        Socket socket = null;
        try {
            socket = new Socket(announcement.getAddress(), DB_SERVER_QUERY_PORT);
            InputStream is = socket.getInputStream();
            OutputStream os = socket.getOutputStream();
            socket.setSoTimeout(3000);
            os.write(DB_SERVER_QUERY_PACKET);
            byte[] response = readResponseWithExpectedSize(is, 2, "database server port query packet");
            if (response.length == 2) {
                setPlayerDBServerPort(announcement.getNumber(), (int)Util.bytesToNumber(response, 0, 2));
            }
        } catch (java.net.ConnectException ce) {
            logger.info("Player " + announcement.getNumber() +
                    " doesn't answer rekordbox port queries, connection refused. Won't attempt to request metadata.");
        } catch (Exception e) {
            logger.warn("Problem requesting database server port number", e);
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException e) {
                    logger.warn("Problem closing database server port request socket", e);
                }
            }
        }
    }

    /**
     * Our announcement listener watches for devices to appear on the network so we can ask them for their database
     * server port, and when they disappear discards all information about them.
     */
    private static DeviceAnnouncementListener announcementListener = new DeviceAnnouncementListener() {
        @Override
        public void deviceFound(final DeviceAnnouncement announcement) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    requestPlayerDBServerPort(announcement);
                }
            }).start();
        }

        @Override
        public void deviceLost(DeviceAnnouncement announcement) {
            setPlayerDBServerPort(announcement.getNumber(), -1);
            clearMetadata(announcement);
        }
    };

    /**
     * Keep track of whether we are running
     */
    private static boolean running = false;

    /**
     * Check whether we are currently running.
     *
     * @return true if track metadata is being sought for all active players
     */
    public static synchronized boolean isRunning() {
        return running;
    }

    /**
     * We process our updates on a separate thread so as not to slow down the high-priority update delivery thread;
     * we perform potentially slow I/O.
     */
    private static Thread queueHandler;

    /**
     * We have received an update that invalidates any previous metadata for that player, so clear it out.
     *
     * @param update the update which means we can have no metadata for the associated player.
     */
    private static synchronized void clearMetadata(CdjStatus update) {
        metadata.remove(update.deviceNumber);
        lastUpdates.remove(update.address);
        // TODO: Add update listener
    }

    /**
     * We have received notification that a device is no longer on the network, so clear out its metadata.
     *
     * @param announcement the packet which reported the device’s disappearance
     */
    private static synchronized void clearMetadata(DeviceAnnouncement announcement) {
        metadata.remove(announcement.getNumber());
        lastUpdates.remove(announcement.getAddress());
    }

    /**
     * We have obtained metadata for a device, so store it.
     *
     * @param update the update which caused us to retrieve this metadata
     * @param data the metadata which we received
     */
    private static synchronized void updateMetadata(CdjStatus update, TrackMetadata data) {
        metadata.put(update.deviceNumber, data);
        lastUpdates.put(update.address, update);
        // TODO: Add update listener
    }

    /**
     * Get all currently known metadata.
     *
     * @return the track information reported by all current players
     */
    public static synchronized Map<Integer, TrackMetadata> getLatestMetadata() {
        return Collections.unmodifiableMap(new TreeMap<Integer, TrackMetadata>(metadata));
    }

    /**
     * Look up the track metadata we have for a given player number.
     *
     * @param player the device number whose track metadata is desired
     * @return information about the track loaded on that player, if available
     */
    public static synchronized TrackMetadata getLatestMetadataFor(int player) {
        return metadata.get(player);
    }

    /**
     * Look up the track metadata we have for a given player, identified by a status update received from that player.
     *
     * @param update a status update from the player for which track metadata is desired
     * @return information about the track loaded on that player, if available
     */
    public static TrackMetadata getLatestMetadataFor(DeviceUpdate update) {
        return getLatestMetadataFor(update.deviceNumber);
    }

    /**
     * Keep track of the devices we are currently trying to get metadata from in response to status updates.
     */
    private final static Set<Integer> activeRequests = new HashSet<Integer>();

    /**
     * Process an update packet from one of the CDJs. See if it has a valid track loaded; if not, clear any
     * metadata we had stored for that player. If so, see if it is the same track we already know about; if not,
     * request the metadata associated with that track.
     *
     * @param update an update packet we received from a CDJ
     */
    private static void handleUpdate(final CdjStatus update) {
        if (update.getTrackType() != CdjStatus.TrackType.REKORDBOX ||
                update.getTrackSourceSlot() == CdjStatus.TrackSourceSlot.NO_TRACK ||
                update.getTrackSourceSlot() == CdjStatus.TrackSourceSlot.UNKNOWN ||
                update.getRekordboxId() == 0) {  // We no longer have metadata for this device
            clearMetadata(update);
        } else {  // We can gather metadata for this device; check if we already looked up this track
            CdjStatus lastStatus = lastUpdates.get(update.address);
            if (lastStatus == null || lastStatus.getTrackSourceSlot() != update.getTrackSourceSlot() ||
                    lastStatus.getTrackSourcePlayer() != update.getTrackSourcePlayer() ||
                    lastStatus.getRekordboxId() != update.getRekordboxId()) {  // We have something new!
                synchronized (activeRequests) {
                    // Make sure we are not already talking to the device before we try hitting it again.
                    if (!activeRequests.contains(update.getTrackSourcePlayer())) {
                        activeRequests.add(update.getTrackSourcePlayer());
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    TrackMetadata data = requestMetadataFrom(update);
                                    if (data != null) {
                                        updateMetadata(update, data);
                                    }
                                } catch (Exception e) {
                                    logger.warn("Problem requesting track metadata from update" + update, e);
                                } finally {
                                    synchronized (activeRequests) {
                                        activeRequests.remove(update.getTrackSourcePlayer());
                                    }
                                }
                            }
                        }).start();
                    }
                }
            }
        }
    }

    /**
     * Start finding track metadata for all active players. Starts the {@link VirtualCdj} if it is not already
     * running, because we need it to send us device status updates to notice when new tracks are loaded.
     *
     * @throws Exception if there is a problem starting the required components
     */
    public static synchronized void start() throws Exception {
        if (!running) {
            DeviceFinder.start();
            DeviceFinder.addDeviceAnnouncementListener(announcementListener);
            for (DeviceAnnouncement device: DeviceFinder.currentDevices()) {
                requestPlayerDBServerPort(device);
            }
            VirtualCdj.start();
            VirtualCdj.addUpdateListener(updateListener);
            queueHandler = new Thread(new Runnable() {
                @Override
                public void run() {
                    while (isRunning()) {
                        try {
                            handleUpdate(pendingUpdates.take());
                        } catch (InterruptedException e) {
                            // Interrupted due to MetadataFinder shutdown, presumably
                        }
                    }
                }
            });
            running = true;
            queueHandler.start();
        }
    }

    /**
     * Stop finding track metadata for all active players.
     */
    public static synchronized void stop() {
        if (running) {
            VirtualCdj.removeUpdateListener(updateListener);
            running = false;
            pendingUpdates.clear();
            queueHandler.interrupt();
            queueHandler = null;
            lastUpdates.clear();
            metadata.clear();
        }
    }
}
