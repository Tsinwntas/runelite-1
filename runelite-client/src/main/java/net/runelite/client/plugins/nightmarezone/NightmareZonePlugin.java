/*
 * Copyright (c) 2018, Nickolaj <https://github.com/fire-proof>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.client.plugins.nightmarezone;

import com.google.inject.Provides;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import javax.inject.Inject;
import lombok.Getter;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.Varbits;
import net.runelite.api.events.BeforeRender;
import net.runelite.api.events.ChatMessage;
import net.runelite.client.events.ConfigChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.client.Notifier;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.botutils.Utils;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.Text;

@PluginDescriptor(
	name = "Nightmare Zone",
	description = "Show NMZ points/absorption and/or notify about expiring potions",
	tags = {"combat", "nmz", "minigame", "notifications"}
)
public class NightmareZonePlugin extends Plugin
{
	private static final int[] NMZ_MAP_REGION = {9033};
	private static final Duration HOUR = Duration.ofHours(1);
	private static final Duration OVERLOAD_DURATION = Duration.ofMinutes(5);

	@Inject
	private Notifier notifier;

	@Inject
	private Client client;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private NightmareZoneConfig config;

	@Inject
	private NightmareZoneOverlay overlay;

	@Getter
	private int pointsPerHour;

	private Instant nmzSessionStartTime;

	// This starts as true since you need to get
	// above the threshold before sending notifications
	private boolean absorptionNotificationSend = true;
	private boolean overloadNotificationSend = false;
	private Instant lastOverload;

	@Override
	protected void startUp() throws Exception
	{
		overlayManager.add(overlay);
		overlay.removeAbsorptionCounter();

		absorptionNotificationSend = true;
		overloadNotificationSend = false;
		Utils.setNmzPlugin(this);
	}

	@Override
	protected void shutDown() throws Exception
	{
		overlayManager.remove(overlay);
		overlay.removeAbsorptionCounter();

		Widget nmzWidget = client.getWidget(WidgetInfo.NIGHTMARE_ZONE);

		if (nmzWidget != null)
		{
			nmzWidget.setHidden(false);
		}

		resetPointsPerHour();
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		overlay.updateConfig();
	}

	@Provides
	NightmareZoneConfig getConfig(ConfigManager configManager)
	{
		return configManager.getConfig(NightmareZoneConfig.class);
	}

	@Subscribe
	public void onBeforeRender(BeforeRender beforeRender)
	{
		if (!isInNightmareZone() || !config.moveOverlay())
		{
			return;
		}

		Widget nmzWidget = client.getWidget(WidgetInfo.NIGHTMARE_ZONE);
		if (nmzWidget != null)
		{
			nmzWidget.setHidden(true);
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (!isInNightmareZone())
		{
			if (!absorptionNotificationSend)
			{
				absorptionNotificationSend = true;
			}

			if (nmzSessionStartTime != null)
			{
				resetPointsPerHour();
			}

			overloadNotificationSend = false;

			return;
		}

		if (config.absorptionNotification())
		{
			checkAbsorption();
		}

		if (overloadNotificationSend && config.overloadNotification() && config.overloadEarlyWarningSeconds() > 0)
		{
			checkOverload();
		}

		if (config.moveOverlay())
		{
			pointsPerHour = calculatePointsPerHour();
		}
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (!isInNightmareZone()
			|| (event.getType() != ChatMessageType.GAMEMESSAGE
			&& event.getType() != ChatMessageType.SPAM))
		{
			return;
		}

		String msg = Text.removeTags(event.getMessage()); //remove color
		if (msg.contains("The effects of overload have worn off, and you feel normal again."))
		{
			// Prevents notification from being sent after overload expiry, if the user disables and re-enables warnings
			overloadNotificationSend = false;

			if (config.overloadNotification())
			{
				notifier.notify("Your overload has worn off");
			}
		}
		else if (msg.contains("A power-up has spawned:"))
		{
			if (msg.contains("Power surge"))
			{
				if (config.powerSurgeNotification())
				{
					notifier.notify(msg);
				}
			}
			else if (msg.contains("Recurrent damage"))
			{
				if (config.recurrentDamageNotification())
				{
					notifier.notify(msg);
				}
			}
			else if (msg.contains("Zapper"))
			{
				if (config.zapperNotification())
				{
					notifier.notify(msg);
				}
			}
			else if (msg.contains("Ultimate force"))
			{
				if (config.ultimateForceNotification())
				{
					notifier.notify(msg);
				}
			}
		}
		else if (msg.contains("You drink some of your overload potion."))
		{
			lastOverload = Instant.now();  // Save time of last overload
			overloadNotificationSend = true;  // Queue up a overload notification once time threshold is reached
		}
	}

	private void checkOverload()
	{
		if (Instant.now().isAfter(lastOverload.plus(OVERLOAD_DURATION).
			minus(Duration.ofSeconds(config.overloadEarlyWarningSeconds()))))
		{
			notifier.notify("Your overload potion is about to expire!");
			overloadNotificationSend = false;
		}
	}

	private void checkAbsorption()
	{
		int absorptionPoints = client.getVarbitValue(Varbits.NMZ_ABSORPTION);

		if (!absorptionNotificationSend)
		{
			if (absorptionPoints < config.absorptionThreshold())
			{
				notifier.notify("Absorption points below: " + config.absorptionThreshold());
				absorptionNotificationSend = true;
			}
		}
		else
		{
			if (absorptionPoints > config.absorptionThreshold())
			{
				absorptionNotificationSend = false;
			}
		}
	}

	private int calculatePointsPerHour()
	{
		Instant now = Instant.now();
		final int currentPoints = client.getVarbitValue(Varbits.NMZ_POINTS);

		if (nmzSessionStartTime == null)
		{
			nmzSessionStartTime = now;
		}

		Duration timeSinceStart = Duration.between(nmzSessionStartTime, now);

		if (!timeSinceStart.isZero())
		{
			return (int) ((double) currentPoints * (double) HOUR.toMillis() / (double) timeSinceStart.toMillis());
		}

		return 0;
	}

	private void resetPointsPerHour()
	{
		nmzSessionStartTime = null;
		pointsPerHour = 0;
	}

	public boolean isInNightmareZone()
	{
		if (client.getLocalPlayer() == null)
		{
			return false;
		}

		// NMZ and the KBD lair uses the same region ID but NMZ uses planes 1-3 and KBD uses plane 0
		return client.getLocalPlayer().getWorldLocation().getPlane() > 0 && Arrays.equals(client.getMapRegions(), NMZ_MAP_REGION);
	}
}
