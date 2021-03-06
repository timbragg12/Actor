package com.insofar.actor;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

import net.minecraft.server.ItemInWorldManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.Packet;
import net.minecraft.server.Packet29DestroyEntity;
import net.minecraft.server.Packet34EntityTeleport;
import net.minecraft.server.Packet35EntityHeadRotation;
import net.minecraft.server.Packet53BlockChange;
import net.minecraft.server.Packet5EntityEquipment;
import net.minecraft.server.World;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.material.MaterialData;

public class ActorAPI
{
	private static ActorPlugin plugin;
	private static MinecraftServer minecraftServer;

	public static void init(ActorPlugin actor)
	{
		plugin = actor;
		minecraftServer = ((CraftServer)Bukkit.getServer()).getServer();
	}

	/**
	 * actor
	 * @param player
	 * Player to take recording from
	 * @return
	 */
	public static EntityActor actor(Player player)
	{
		return actor(player, "Actor");
	}

	/**
	 * actor
	 * @param player
	 * Player to take recording from
	 * @param actorName
	 * name to give spawned actor
	 * @param viewerName
	 * name of viewer ("all" for everyone in player's world)
	 * @return
	 */
	public static EntityActor actor(Player player, String actorName)
	{
		org.bukkit.World world;

		world = player.getWorld();

		// Authoring commands must have an author
		Author author = getAuthor(player);

		if (author.getCurrentRecording() == null)
		{
			player.sendMessage("ERROR: No recording. You have nothing recorded.");
			return null;
		}

		if (author.isRecording())
		{
			player.sendMessage("ERROR: Recording. You are currently recording. Stop recording first.");
			return null;
		}

		EntityActor result = actor(author.getCurrentRecording(), actorName, world);

		author.setCurrentRecording(null);

		if (result != null)
		{
			player.sendMessage("Spawned entity with id = "+result.id);
			return result;
		}
		else
		{
			return null;
		}
	}

	/**
	 * actor
	 * @param recording
	 * Recording to use for the new actor
	 * @param actorName
	 * name to give spawned actor
	 * @param viewerPlayer
	 * viewer who can see this actor (null for everyone in the same world)
	 * @param world
	 * viewer's world
	 * @return The EntityActor which was created.
	 */
	public static EntityActor actor(Recording recording, String actorName, org.bukkit.World world)
	{
		return actor(recording, actorName, world, 0, 0, 0);
	}

	public static EntityActor actor(Recording recording, String actorName, org.bukkit.World world, int x, int y, int z)
	{
		World w = ((CraftWorld) world).getHandle();
		ItemInWorldManager iw = new ItemInWorldManager(w);

		// Setup EntityActor
		EntityActor actor = new EntityActor(minecraftServer, w, actorName, iw);
		actor.setRecording(recording);
		actor.setActorName(actorName);

		// Setup translation
		actor.setTranslateX(x);
		actor.setTranslateY(y);
		actor.setTranslateZ(z);

		actor.spawn();

		return actor;
	}

	/**
	 * dub an actor
	 */
	public static EntityActor dub(EntityActor actor, String newName,
			Player viewerPlayer, org.bukkit.World world, int x, int y, int z)
	{
		World w = ((CraftWorld) world).getHandle();
		ItemInWorldManager iw = new ItemInWorldManager(w);
		EntityActor newActor = new EntityActor(minecraftServer, w, newName, iw);
		newActor.setTranslateX(actor.getTranslateX() + x);
		newActor.setTranslateY(actor.getTranslateY() + y);
		newActor.setTranslateZ(actor.getTranslateZ() + z);

		newActor.setRecording(new Recording());
		newActor.getRecording().recordedPackets = actor.getRecording().recordedPackets;

		newActor.setActorName(newName);

		newActor.spawn();

		return newActor;
	}

	/**
	 * actorRemove
	 * @param actor Actor to remove
	 */
	public static boolean actorRemove(EntityActor actor)
	{
		actor.sendPacket(new Packet29DestroyEntity(actor.id));
		return true;
	}

	/**
	 * record command
	 * @param player
	 * @return
	 */
	public static boolean recordAuthor(Player player)
	{
		Author author = getAuthor(player);

		if (author.isRecording())
		{
			player.sendMessage("You are already recording.");
			return true;
		}

		// Create new recording
		author.setCurrentRecording(new Recording());
		author.setRecording(true);
		player.sendMessage("Recording.");

		return record(player,author.getCurrentRecording());
	}

	/**
	 * Record player into specified recording.
	 * @param player
	 * @param recording
	 * @return
	 */
	public static boolean record(Player player, Recording recording)
	{
		// Setup jumpstart packets on recording
		// 	Packet34EntityTeleport
		Location l = player.getLocation();
		Packet34EntityTeleport tp = new Packet34EntityTeleport();
		tp.a = player.getEntityId(); // Entity id will be replaced on spawn/playback
		tp.b = floor_double(l.getX() * 32D);
		tp.c = floor_double(l.getY() * 32D);
		tp.d = floor_double(l.getZ() * 32D);
		tp.e = (byte)(int)((l.getYaw() * 256F) / 360F);
		tp.f = (byte)(int)((l.getPitch() * 256F) / 360F);
		recording.recordPacket(tp,true);

		// 	Packet35HeadRotation
		Packet35EntityHeadRotation hr = new Packet35EntityHeadRotation(tp.a, tp.e);
		recording.recordPacket(hr,true);

		// Packet5EntityEquipment
		// Should really use five of these on a new spawn for all equipment.
		Packet5EntityEquipment ep = new Packet5EntityEquipment();
		ep.b = 0;
		ep.c = player.getInventory().getItemInHand().getTypeId();
		if (ep.c == 0) ep.c = -1;
		recording.recordPacket(ep,true);

		return true;
	}

	public static int floor_double(double d)
	{
		int i = (int)d;
		return d >= i ? i : i - 1;
	}

	/**
	 * Reset the author's current recording and give back any blocks used.
	 * @param author
	 */
	public static void resetAuthor(Player player)
	{
		Author author = plugin.authors.get(player.getName());

		// Rewind the player's current recording and give back any blocks used.
		rewindAuthor(author);
	}

	/**
	 * Rewind the author's current recording. This rewinds the current
	 * recording before it has been assigned to an actor.
	 */
	public static void rewindAuthor(Author author)
	{
		MinecraftServer minecraftServer = ((CraftServer)Bukkit.getServer()).getServer();

		if (author == null)
			return;

		if (author.isRecording())
		{
			author.getPlayer().sendMessage("Stopping recording.");
			author.setRecording(false);
		}

		if (author.getCurrentRecording() == null ||
				author.getCurrentRecording().rewindPackets.size() == 0)
		{
			// Nothing to do
			return;
		}

		org.bukkit.World world = author.getPlayer().getWorld();

		ArrayList<Packet> rewindPackets = author.getCurrentRecording().getRewindPackets();

		for (Packet p : rewindPackets)
		{
			if (p instanceof Packet53BlockChange)
			{
				// get current block type
				Location location = new Location(world,
						((Packet53BlockChange) p).a,
						((Packet53BlockChange) p).b,
						((Packet53BlockChange) p).c);
				int currType = world.getBlockTypeIdAt(location);
				int currMaterial = world.getBlockAt(location).getData();

				// Set the block in the server's world
				if (currType != ((Packet53BlockChange) p).data )
				{
					Block block = world.getBlockAt(location);
					block.setTypeId(((Packet53BlockChange) p).data);
					block.setData((byte)((Packet53BlockChange) p).material);
					minecraftServer.serverConfigurationManager.a(author.getPlayer().getName(),p);

					if (currType != 0)
					{
						// Do an item drop so player gets the blocks back
						ItemStack is = new ItemStack( currType ,1);
						MaterialData data = new MaterialData(currMaterial);
						is.setData(data);
						author.getPlayer().getWorld().dropItemNaturally(author.getPlayer().getLocation(), is);
					}
				}
			}
		}

		author.getCurrentRecording().rewind();

	}

	/**
	 * stopRecording
	 * 
	 * @param player
	 * @return
	 */
	public static boolean stopRecording(Player player)
	{
		Author author = getAuthor(player);

		if (author.getCurrentRecording() != null)
		{
			author.setRecording(false);
			author.getCurrentRecording().rewind();
			player.sendMessage("Recording stopped.");
		}
		else
		{
			player.sendMessage("You have no recording.");
		}

		return true;
	}

	/********************************************************************
	 * 
	 * Utilities
	 * 
	 *******************************************************************/

	/**
	 * Get the author given the player
	 * @param p
	 * @return
	 */
	public static Author getAuthor(Player p)
	{
		Author author = plugin.authors.get(p.getName());

		// Set up an author if needed
		if (author == null)
		{
			author = new Author();
			author.setPlayer(p);
			plugin.authors.put(p.getName(), author);
		}
		return author;
	}

	/**
	 * Record the player's packet into all active recordings (which contain that player).
	 * Accepts data and type for rewind packets. 
	 * @param player
	 * @param packet
	 * @param data
	 * @param material
	 */
	public static void recordPlayerPacket(Player player, Packet packet)
	{
		recordPlayerPacket(player, packet, 0, 0);

	}

	/**
	 * Record the player's packet into all active recordings (which contain that player).
	 * @param packet
	 * @param recordings
	 */
	public static void recordPlayerPacket(Player player, Packet packet, int data, int material)
	{
		for (Recording r : getRecordingsForPlayer(player))
		{
			if (ActorPlugin.getInstance().getRootConfig().debugVerbose)
			{
				plugin.getLogger().info("Recorded packet for "+player.getName());
			}
			r.recordPacket(packet);

			// Handle rewind packets
			if (packet instanceof Packet53BlockChange)
			{
				addRewindForBlockChange(r, (Packet53BlockChange)packet, data, material);
			}
		}
	}

	/**
	 * Add a rewind packet for a block change 
	 * @param r
	 * @param p
	 */
	public static void addRewindForBlockChange(Recording r, Packet53BlockChange p, int data, int material)
	{
		Packet53BlockChange changeBack = new Packet53BlockChange();

		changeBack.a = p.a;
		changeBack.b = p.b;
		changeBack.c = p.c;
		changeBack.data = data;
		changeBack.material = material;

		r.addRewindPacket(changeBack);
	}

	/**
	 * Returns a set of all recordings in which this player is being recorded.
	 * May be recording themselves or in multiple troupes.
	 */
	public static Set<Recording>getRecordingsForPlayer(Player p)
	{
		Set<Recording> result = new HashSet<Recording>();
		// Troupes
		for (Author a : ActorPlugin.getInstance().authors.values())
		{
			if (a.getTroupeMembers().contains(p))
			{
				Recording r = a.getTroupRecMap().get(p.getName());
				if (r != null)
					result.add(a.getTroupRecMap().get(p.getName()));
			}
		}

		return result;
	}

	/**
	 * Is the player in an active recording?
	 */
	public static boolean isPlayerBeingRecorded(Player p)
	{
		Author author = plugin.authors.get(p.getName());

		// Author's personal recording
		if (author != null && author.isRecording())
		{
			return true;
		}

		// Troupes
		for (Author a : ActorPlugin.getInstance().authors.values())
		{
			if (a.getTroupeMembers().contains(p) && a.isRecording())
			{
				return true;
			}
		}

		return false;
	}

}
