/*******************************************************************************
 * OpenEMS - Open Source Energy Management System
 * Copyright (c) 2016 FENECON GmbH and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 *
 * Contributors:
 *   FENECON GmbH - initial API and implementation and initial documentation
 *******************************************************************************/
package io.openems.core;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBasedTable;
import com.google.common.collect.HashBiMap;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Table;
import com.google.gson.JsonObject;

import io.openems.api.bridge.Bridge;
import io.openems.api.channel.Channel;
import io.openems.api.channel.ConfigChannel;
import io.openems.api.channel.ReadChannel;
import io.openems.api.channel.WriteChannel;
import io.openems.api.controller.Controller;
import io.openems.api.device.nature.DeviceNature;
import io.openems.api.doc.ThingDoc;
import io.openems.api.exception.ReflectionException;
import io.openems.api.persistence.Persistence;
import io.openems.api.scheduler.Scheduler;
import io.openems.api.thing.Thing;
import io.openems.core.utilities.ConfigUtils;
import io.openems.core.utilities.InjectionUtils;
import io.openems.core.utilities.JsonUtils;

public class ThingRepository {
	private final static Logger log = LoggerFactory.getLogger(ThingRepository.class);

	private static ThingRepository instance;

	public static synchronized ThingRepository getInstance() {
		if (ThingRepository.instance == null) {
			ThingRepository.instance = new ThingRepository();
		}
		return ThingRepository.instance;
	}

	private ThingRepository() {
		classRepository = ClassRepository.getInstance();
		// Fill "availableThingClasses"
		// Controller
		try {
			Set<Class<? extends Thing>> clazzes = ConfigUtils.getAvailableClasses("io.openems.impl.controller",
					Controller.class, "Controller");
			for (Class<? extends Thing> clazz : clazzes) {
				ThingDoc description = ConfigUtils.getThingDescription(clazz);
				availableThingClasses.put(Controller.class, clazz, description);
			}
		} catch (ReflectionException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	private final ClassRepository classRepository;
	private final BiMap<String, Thing> thingIds = HashBiMap.create();
	private HashMultimap<Class<? extends Thing>, Thing> thingClasses = HashMultimap.create();
	private Set<Bridge> bridges = new HashSet<>();
	private Set<Scheduler> schedulers = new HashSet<>();
	private Set<Persistence> persistences = new HashSet<>();
	private Set<DeviceNature> deviceNatures = new HashSet<>();
	private final Table<Thing, String, Channel> thingChannels = HashBasedTable.create();
	private HashMultimap<Thing, ConfigChannel<?>> thingConfigChannels = HashMultimap.create();
	private HashMultimap<Thing, WriteChannel<?>> thingWriteChannels = HashMultimap.create();
	private final Table<Class<? extends Thing>, Class<? extends Thing>, ThingDoc> availableThingClasses = HashBasedTable
			.create();

	/**
	 * Add a Thing to the Repository and cache its Channels and other information for later usage.
	 *
	 * @param thing
	 */
	public synchronized void addThing(Thing thing) {
		if (thingIds.containsValue(thing)) {
			// Thing was already added
			return;
		}
		// Add to thingIds
		thingIds.forcePut(thing.id(), thing);

		// Add to thingClasses
		thingClasses.put(thing.getClass(), thing);

		// Add to bridges
		if (thing instanceof Bridge) {
			bridges.add((Bridge) thing);
		}

		// Add to schedulers
		if (thing instanceof Scheduler) {
			schedulers.add((Scheduler) thing);
		}

		// Add to persistences
		if (thing instanceof Persistence) {
			persistences.add((Persistence) thing);
		}

		// Add to persistences
		if (thing instanceof DeviceNature) {
			deviceNatures.add((DeviceNature) thing);
		}

		// Add Channels to thingChannels, thingConfigChannels and thingWriteChannels
		Set<Member> members = classRepository.getThingChannels(thing.getClass());
		for (Member member : members) {
			try {
				List<Channel> channels = new ArrayList<>();
				if (member instanceof Method) {
					if (((Method) member).getReturnType().isArray()) {
						Channel[] ch = (Channel[]) ((Method) member).invoke(thing);
						for (Channel c : ch) {
							channels.add(c);
						}
					} else {
						// It's a Method with ReturnType Channel
						channels.add((Channel) ((Method) member).invoke(thing));
					}
				} else if (member instanceof Field) {
					// It's a Field with Type Channel
					channels.add((Channel) ((Field) member).get(thing));
				} else {
					continue;
				}
				if (channels.isEmpty()) {
					log.error(
							"Channel is returning null! Thing [" + thing.id() + "], Member [" + member.getName() + "]");
					continue;
				}
				for (Channel channel : channels) {
					// Add Channel to thingChannels
					thingChannels.put(thing, channel.id(), channel);

					// Add Channel to configChannels
					if (channel instanceof ConfigChannel) {
						thingConfigChannels.put(thing, (ConfigChannel<?>) channel);
					}

					// Add Channel to writeChannels
					if (channel instanceof WriteChannel) {
						thingWriteChannels.put(thing, (WriteChannel<?>) channel);
					}

					// Register Databus as listener
					if (channel instanceof ReadChannel) {
						Databus databus = Databus.getInstance();
						((ReadChannel<?>) channel).updateListener(databus);
						((ReadChannel<?>) channel).changeListener(databus);
					}

				}
			} catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
				log.warn("Unable to add Channel. Member [" + member.getName() + "]", e);
			}
		}

	}

	/**
	 * Remove a Thing from the Repository.
	 *
	 * @param thing
	 */
	public synchronized void removeThing(String thingId) {
		Thing thing = thingIds.get(thingId);
		removeThing(thing);
	}

	/**
	 * Remove a Thing from the Repository.
	 *
	 * @param thing
	 */
	public synchronized void removeThing(Thing thing) {
		// Remove from thingIds
		thingIds.remove(thing.id());

		// Remove from thingClasses
		thingClasses.remove(thing.getClass(), thing);

		// Remove from bridges
		if (thing instanceof Bridge) {
			bridges.remove(thing);
		}

		// Remove from schedulers
		if (thing instanceof Scheduler) {
			schedulers.remove(thing);
		}

		// Remove from persistences
		if (thing instanceof Persistence) {
			persistences.remove(thing);
		}

		// Remove from persistences
		if (thing instanceof DeviceNature) {
			deviceNatures.remove(thing);
		}

		// Remove controller
		if (thing instanceof Controller) {
			Controller controller = (Controller) thing;
			for (Scheduler scheduler : getSchedulers()) {
				scheduler.removeController(controller);
			}
		}
		// TODO further cleaning if required
	}

	public Thing getThing(String thingId) {
		Thing thing = thingIds.get(thingId);
		return thing;
	}

	public Set<Thing> getThings() {
		return Collections.unmodifiableSet(this.thingIds.values());
	}

	/**
	 * Returns all Channels for this Thing.
	 *
	 * @param thing
	 * @return
	 */
	public synchronized Collection<Channel> getChannels(Thing thing) {
		return Collections.unmodifiableCollection(thingChannels.row(thing).values());
	}

	/**
	 * Returns all Config-Channels.
	 *
	 * @return
	 */
	public synchronized Collection<ConfigChannel<?>> getConfigChannels() {
		return Collections.unmodifiableCollection(thingConfigChannels.values());
	}

	/**
	 * Returns all Config-Channels for this Thing.
	 *
	 * @param thing
	 * @return
	 */
	public synchronized Set<ConfigChannel<?>> getConfigChannels(Thing thing) {
		return Collections.unmodifiableSet(thingConfigChannels.get(thing));
	}

	/**
	 * Returns all Write-Channels for this Thing.
	 *
	 * @param thing
	 * @return
	 */
	public synchronized Set<WriteChannel<?>> getWriteChannels(Thing thing) {
		return Collections.unmodifiableSet(thingWriteChannels.get(thing));
	}

	/**
	 * Returns all Write-Channels.
	 *
	 * @param thing
	 * @return
	 */
	public synchronized Collection<WriteChannel<?>> getWriteChannels() {
		return Collections.unmodifiableCollection(thingWriteChannels.values());
	}

	/**
	 * Returns all Persistence-Workers.
	 *
	 * @param thing
	 * @return
	 */
	public synchronized Set<Persistence> getPersistences() {
		return Collections.unmodifiableSet(persistences);
	}

	public synchronized Set<Class<? extends Thing>> getThingClasses() {
		return Collections.unmodifiableSet(thingClasses.keySet());
	}

	public synchronized Set<Thing> getThingsByClass(Class<? extends Thing> clazz) {
		return Collections.unmodifiableSet(thingClasses.get(clazz));
	}

	public synchronized Set<Thing> getThingsAssignableByClass(Class<? extends Thing> clazz) {
		Set<Thing> things = new HashSet<>();
		for (Class<? extends Thing> subclazz : thingClasses.keySet()) {
			if (clazz.isAssignableFrom(subclazz)) {
				things.addAll(thingClasses.get(subclazz));
			}

		}
		return Collections.unmodifiableSet(things);
	}

	public synchronized Optional<Thing> getThingById(String id) {
		return Optional.ofNullable(thingIds.get(id));
	}

	public synchronized Set<Bridge> getBridges() {
		return Collections.unmodifiableSet(bridges);
	}

	public synchronized Set<Scheduler> getSchedulers() {
		return Collections.unmodifiableSet(schedulers);
	}

	public synchronized Set<DeviceNature> getDeviceNatures() {
		return Collections.unmodifiableSet(deviceNatures);
	}

	public Optional<Channel> getChannel(String thingId, String channelId) {
		Thing thing = thingIds.get(thingId);
		if (thing == null) {
			return Optional.empty();
		}
		Channel channel = thingChannels.row(thing).get(channelId);
		return Optional.ofNullable(channel);
	}

	public Optional<Channel> getChannelByAddress(String address) {
		String[] args = address.split("/");
		if (args.length == 2) {
			return getChannel(args[0], args[1]);
		}
		return Optional.empty();
	}

	public Controller createController(JsonObject jController) throws ReflectionException {
		String controllerClass = JsonUtils.getAsString(jController, "class");
		Controller controller;
		if (jController.has("id")) {
			String id = JsonUtils.getAsString(jController, "id");
			controller = (Controller) InjectionUtils.getThingInstance(controllerClass, id);
		} else {
			controller = (Controller) InjectionUtils.getThingInstance(controllerClass);
		}
		log.debug("Add Controller[" + controller.id() + "], Implementation[" + controller.getClass().getSimpleName()
				+ "]");
		this.addThing(controller);
		ConfigUtils.injectConfigChannels(this.getConfigChannels(controller), jController);
		return controller;
	}

	public List<ThingDoc> getAvailableControllers() {
		List<ThingDoc> descriptions = new ArrayList<>();
		availableThingClasses.row(Controller.class).forEach((clazz, description) -> {
			descriptions.add(description);
		});
		return Collections.unmodifiableList(descriptions);
	}
}
