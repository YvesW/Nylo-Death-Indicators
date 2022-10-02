package com.infernostats;

import com.google.common.annotations.VisibleForTesting;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.inject.Inject;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.GraphicID;
import net.runelite.api.GraphicsObject;
import net.runelite.api.HitsplatID;
import net.runelite.api.ItemID;
import net.runelite.api.NPC;
import net.runelite.api.NpcID;
import net.runelite.api.Player;
import net.runelite.api.PlayerComposition;
import net.runelite.api.Renderable;
import net.runelite.api.Skill;
import net.runelite.api.VarPlayer;
import net.runelite.api.events.BeforeRender;
import net.runelite.api.events.FakeXpDrop;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.NpcSpawned;
import net.runelite.api.events.StatChanged;
import net.runelite.api.kit.KitType;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.callback.Hooks;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.party.PartyService;
import net.runelite.client.party.WSClient;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.util.Text;
import org.apache.commons.lang3.ArrayUtils;

@PluginDescriptor(
	name = "Nylo Death Indicators",
	description = "Hide dead nylos faster"
)
public class NyloDeathIndicatorsPlugin extends Plugin
{
	private int partySize = 0;
	private boolean isInNyloRegion = false;
	private final ArrayList<Nylocas> nylos = new ArrayList<>();
	private final ArrayList<Nylocas> deadNylos = new ArrayList<>();
	private final Map<Skill, Integer> previousXpMap = new EnumMap<>(Skill.class);

	private static final Set<Integer> CHINCHOMPAS = new HashSet<>(Arrays.asList(
		ItemID.CHINCHOMPA_10033,
		ItemID.RED_CHINCHOMPA_10034,
		ItemID.BLACK_CHINCHOMPA
	));

	private static final Set<Integer> POWERED_STAVES = new HashSet<>(Arrays.asList(
		ItemID.SANGUINESTI_STAFF,
		ItemID.TRIDENT_OF_THE_SEAS_FULL,
		ItemID.TRIDENT_OF_THE_SEAS,
		ItemID.TRIDENT_OF_THE_SWAMP,
		ItemID.TRIDENT_OF_THE_SWAMP_E,
		ItemID.HOLY_SANGUINESTI_STAFF
	));

	private static final Set<Integer> NYLO_MELEE_WEAPONS = new HashSet<>(Arrays.asList(
		ItemID.SWIFT_BLADE, ItemID.HAM_JOINT, ItemID.GOBLIN_PAINT_CANNON,
		ItemID.DRAGON_CLAWS, ItemID.DRAGON_SCIMITAR,
		ItemID.ABYSSAL_BLUDGEON, ItemID.INQUISITORS_MACE,
		ItemID.SARADOMIN_SWORD, ItemID.SARADOMINS_BLESSED_SWORD,
		ItemID.GHRAZI_RAPIER, ItemID.HOLY_GHRAZI_RAPIER,
		ItemID.ABYSSAL_WHIP, ItemID.ABYSSAL_WHIP_OR,
		ItemID.FROZEN_ABYSSAL_WHIP, ItemID.VOLCANIC_ABYSSAL_WHIP,
		ItemID.ABYSSAL_TENTACLE, ItemID.ABYSSAL_TENTACLE_OR
	));

	private static final Set<Integer> MULTIKILL_MELEE_WEAPONS = new HashSet<>(Arrays.asList(
		ItemID.SCYTHE_OF_VITUR_UNCHARGED, ItemID.SCYTHE_OF_VITUR,
		ItemID.HOLY_SCYTHE_OF_VITUR_UNCHARGED, ItemID.HOLY_SCYTHE_OF_VITUR,
		ItemID.SANGUINE_SCYTHE_OF_VITUR_UNCHARGED, ItemID.SANGUINE_SCYTHE_OF_VITUR,
		ItemID.DINHS_BULWARK
	));

	private static final int BARRAGE_ANIMATION = 1979;
	private static final int NYLOCAS_REGION_ID = 13122;

	private final Hooks.RenderableDrawListener drawListener = this::shouldDraw;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private WSClient wsClient;

	@Inject
	private PartyService party;

	@Inject
	private Hooks hooks;

	@Override
	protected void startUp()
	{
		clientThread.invoke(this::initializePreviousXpMap);

		hooks.registerRenderableDrawListener(drawListener);
		wsClient.registerMessage(NpcDamaged.class);
	}

	@Override
	protected void shutDown()
	{
		hooks.unregisterRenderableDrawListener(drawListener);
		wsClient.unregisterMessage(NpcDamaged.class);
	}

	private void initializePreviousXpMap()
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			previousXpMap.clear();
		}
		else
		{
			for (final Skill skill : Skill.values())
			{
				previousXpMap.put(skill, client.getSkillExperience(skill));
			}
		}
	}

	@Subscribe
	protected void onGameTick(GameTick event)
	{
		if (!isInNyloRegion)
		{
			isInNyloRegion = isInNylocasRegion();
			if (isInNyloRegion)
			{
				partySize = getParty().size();
			}
		}
		else
		{
			isInNyloRegion = isInNylocasRegion();
			if (!isInNyloRegion)
			{
				this.nylos.clear();
				this.deadNylos.clear();
			}
		}

		Iterator<Nylocas> nylocasIterator = deadNylos.iterator();
		while (nylocasIterator.hasNext())
		{
			Nylocas nylocas = nylocasIterator.next();
			nylocas.setHidden(nylocas.getHidden() + 1);

			final boolean isDead = nylocas.getNpc().getHealthRatio() == 0;
			if (nylocas.getHidden() > 5 && !isDead)
			{
				nylocas.setHidden(0);
				nylocasIterator.remove();
			}
		}
	}

	@Subscribe
	protected void onBeforeRender(BeforeRender event)
	{
		if (!isInNyloRegion)
		{
			return;
		}

		for (Nylocas nylo : deadNylos)
		{
			nylo.getNpc().setDead(true);
		}
	}

	@Subscribe
	protected void onNpcSpawned(NpcSpawned event)
	{
		if (!isInNyloRegion)
		{
			return;
		}

		int smSmallHP = -1;
		int smBigHP = -1;
		int bigHP = -1;
		int smallHP = -1;

		switch (this.partySize)
		{
			case 1:
				bigHP = 16;
				smallHP = 8;
				smSmallHP = 2;
				smBigHP = 3;
				break;
			case 2:
				bigHP = 16;
				smallHP = 8;
				smSmallHP = 4;
				smBigHP = 6;
				break;
			case 3:
				bigHP = 16;
				smallHP = 8;
				smSmallHP = 6;
				smBigHP = 9;
				break;
			case 4:
				bigHP = 19;
				smallHP = 9;
				smSmallHP = 8;
				smBigHP = 12;
				break;
			case 5:
				bigHP = 22;
				smallHP = 11;
				smSmallHP = 10;
				smBigHP = 15;
				break;
		}

		final NPC npc = event.getNpc();
		final int index = npc.getIndex();
		switch (npc.getId()) {
			case NpcID.NYLOCAS_ISCHYROS_8342:
			case NpcID.NYLOCAS_TOXOBOLOS_8343:
			case NpcID.NYLOCAS_HAGIOS:
			case NpcID.NYLOCAS_ISCHYROS_10791:
			case NpcID.NYLOCAS_TOXOBOLOS_10792:
			case NpcID.NYLOCAS_HAGIOS_10793:
				this.nylos.add(new Nylocas(npc, index, smallHP));
				break;
			case NpcID.NYLOCAS_ISCHYROS_8345:
			case NpcID.NYLOCAS_TOXOBOLOS_8346:
			case NpcID.NYLOCAS_HAGIOS_8347:
			case NpcID.NYLOCAS_ISCHYROS_8351:
			case NpcID.NYLOCAS_TOXOBOLOS_8352:
			case NpcID.NYLOCAS_HAGIOS_8353:
			case NpcID.NYLOCAS_ISCHYROS_10783:
			case NpcID.NYLOCAS_TOXOBOLOS_10784:
			case NpcID.NYLOCAS_HAGIOS_10785:
			case NpcID.NYLOCAS_ISCHYROS_10794:
			case NpcID.NYLOCAS_TOXOBOLOS_10795:
			case NpcID.NYLOCAS_HAGIOS_10796:
			case NpcID.NYLOCAS_ISCHYROS_10800:
			case NpcID.NYLOCAS_TOXOBOLOS_10801:
			case NpcID.NYLOCAS_HAGIOS_10802:
				this.nylos.add(new Nylocas(npc, index, bigHP));
				break;
			case NpcID.NYLOCAS_ISCHYROS_10774:
			case NpcID.NYLOCAS_TOXOBOLOS_10775:
			case NpcID.NYLOCAS_HAGIOS_10776:
				this.nylos.add(new Nylocas(npc, index, smSmallHP));
				break;
			case NpcID.NYLOCAS_ISCHYROS_10777:
			case NpcID.NYLOCAS_TOXOBOLOS_10778:
			case NpcID.NYLOCAS_HAGIOS_10779:
				this.nylos.add(new Nylocas(npc, index, smBigHP));
		}
	}

	@Subscribe
	protected void onHitsplatApplied(HitsplatApplied event)
	{
		if (!isInNyloRegion)
		{
			return;
		}

		Actor actor = event.getActor();
		if (actor instanceof NPC)
		{
			final int npcIndex = ((NPC)actor).getIndex();
			final int damage = event.getHitsplat().getAmount();

			for (Nylocas nylocas : this.nylos)
			{
				if (nylocas.getNpcIndex() != npcIndex)
				{
					continue;
				}

				if (event.getHitsplat().getHitsplatType() == HitsplatID.HEAL)
				{
					nylocas.setHp(nylocas.getHp() + damage);
				}
				else
				{
					nylocas.setHp(nylocas.getHp() - damage);
				}

				nylocas.setQueuedDamage(Math.max(0, nylocas.getQueuedDamage() - damage));
			}
		}
	}

	@Subscribe
	protected void onNpcDamaged(NpcDamaged event)
	{
		if (!isInNyloRegion)
		{
			return;
		}

		clientThread.invokeLater(() -> {
			final int npcIndex = event.getNpcIndex();
			final int damage = event.getDamage();

			for (Nylocas nylocas : this.nylos)
			{
				if (nylocas.getNpcIndex() != npcIndex)
				{
					continue;
				}

				nylocas.setQueuedDamage(nylocas.getQueuedDamage() + damage);

				if (nylocas.getHp() - nylocas.getQueuedDamage() <= 0)
				{
					if (deadNylos.stream().noneMatch(deadNylo -> deadNylo.getNpcIndex() == npcIndex))
					{
						deadNylos.add(nylocas);
					}
				}
			}
		});
	}

	@Subscribe
	protected void onFakeXpDrop(FakeXpDrop event)
	{
		processXpDrop(event.getSkill(), event.getXp());
	}

	@Subscribe
	protected void onStatChanged(StatChanged event)
	{
		preProcessXpDrop(event.getSkill(), event.getXp());
	}

	private void preProcessXpDrop(Skill skill, int xp)
	{
		final int xpAfter = client.getSkillExperience(skill);
		final int xpBefore = previousXpMap.getOrDefault(skill, -1);

		previousXpMap.put(skill, xpAfter);

		if (xpBefore == -1 || xpAfter <= xpBefore)
		{
			return;
		}

		processXpDrop(skill, xpAfter - xpBefore);
	}

	private void processXpDrop(Skill skill, final int xp)
	{
		if (!isInNylocasRegion())
		{
			return;
		}

		int damage = 0;

		Player player = client.getLocalPlayer();
		if (player == null)
		{
			return;
		}

		PlayerComposition playerComposition = player.getPlayerComposition();
		if (playerComposition == null)
		{
			return;
		}

		int weaponUsed = playerComposition.getEquipmentId(KitType.WEAPON);
		int attackStyle = client.getVarpValue(VarPlayer.ATTACK_STYLE.getId());

		boolean isChinchompa = CHINCHOMPAS.contains(weaponUsed);
		boolean isPoweredStaff = POWERED_STAVES.contains(weaponUsed);

		if (player.getAnimation() == BARRAGE_ANIMATION)
		{
			return;
		}

		switch (skill)
		{
			case MAGIC:
				// 0 == Accurate 1 | 1 == Accurate 2 | 3 == Defensive
				if (isPoweredStaff && attackStyle != 3)
				{
					damage = (int) ((double) xp / 2.0D);
				}

				break;
			case ATTACK:
			case STRENGTH:
			case DEFENCE:
				boolean isDefensiveCast = attackStyle == 3;

				if (MULTIKILL_MELEE_WEAPONS.contains(weaponUsed))
				{
					return;
				}
				else if (NYLO_MELEE_WEAPONS.contains(weaponUsed))
				{
					damage = (int) ((double) xp / 4.0D);
				}
				else if (isPoweredStaff && isDefensiveCast)
				{
					damage = xp;
				}

				break;
			case RANGED:
				if (isChinchompa)
				{
					return;
				}

				if (attackStyle == 3)
				{
					damage = (int) ((double) xp / 2.0D);
				}
				else
				{
					damage = (int) ((double) xp / 4.0D);
				}
		}

		sendDamage(player, damage);
	}

	private void sendDamage(Player player, int damage)
	{
		if (damage <= 0)
		{
			return;
		}

		Actor interacted = player.getInteracting();
		if (interacted instanceof NPC)
		{
			NPC interactedNPC = (NPC) interacted;
			final int npcIndex = interactedNPC.getIndex();
			final NpcDamaged npcDamaged = new NpcDamaged(npcIndex, damage);

			if (party.isInParty())
			{
				clientThread.invokeLater(() -> party.send(npcDamaged));
			}
			else
			{
				onNpcDamaged(npcDamaged);
			}
		}
	}

	public List<String> getParty()
	{
		List<String> team = new ArrayList<>();

		for (int i = 330; i < 335; i++)
		{
			team.add(client.getVarcStrValue(i));
		}

		return team.stream()
			.map(Text::sanitize)
			.filter(name -> !name.isEmpty())
			.collect(Collectors.toList());
	}

	private boolean isInNylocasRegion()
	{
		return client.getMapRegions() != null && ArrayUtils.contains(client.getMapRegions(), NYLOCAS_REGION_ID);
	}

	@VisibleForTesting
	boolean shouldDraw(Renderable renderable, boolean drawingUI)
	{
		if (renderable instanceof NPC)
		{
			return deadNylos.stream()
				.noneMatch(nylocas -> nylocas.getNpcIndex() == ((NPC) renderable).getIndex());
		}
		else if (renderable instanceof GraphicsObject)
		{
			switch (((GraphicsObject) renderable).getId())
			{
				case GraphicID.MELEE_NYLO_DEATH:
				case GraphicID.RANGE_NYLO_DEATH:
				case GraphicID.MAGE_NYLO_DEATH:
				case GraphicID.MELEE_NYLO_EXPLOSION:
				case GraphicID.RANGE_NYLO_EXPLOSION:
				case GraphicID.MAGE_NYLO_EXPLOSION:
					return false;
			}
		}

		return true;
	}
}