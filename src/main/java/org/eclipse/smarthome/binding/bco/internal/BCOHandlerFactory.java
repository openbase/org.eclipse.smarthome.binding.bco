package org.eclipse.smarthome.binding.bco.internal;

/*-
 * #%L
 * BCO Binding
 * %%
 * Copyright (C) 2018 - 2021 openbase.org
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Lesser Public License for more details.
 * 
 * You should have received a copy of the GNU General Lesser Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/lgpl-3.0.html>.
 * #L%
 */

import com.google.protobuf.ByteString;
import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.smarthome.core.thing.Thing;
import org.eclipse.smarthome.core.thing.ThingTypeUID;
import org.eclipse.smarthome.core.thing.binding.BaseThingHandlerFactory;
import org.eclipse.smarthome.core.thing.binding.ThingHandler;
import org.eclipse.smarthome.core.thing.binding.ThingHandlerFactory;
import org.openbase.bco.authentication.lib.SessionManager;
import org.openbase.bco.dal.remote.layer.unit.Units;
import org.openbase.bco.registry.remote.Registries;
import org.openbase.bco.registry.remote.login.BCOLogin;
import org.openbase.bco.registry.unit.lib.UnitRegistry;
import org.openbase.jps.core.JPService;
import org.openbase.jps.exception.JPServiceException;
import org.openbase.jul.exception.CouldNotPerformException;
import org.openbase.jul.exception.NotAvailableException;
import org.openbase.jul.extension.rsb.com.RSBSharedConnectionConfig;
import org.openbase.jul.extension.rsb.com.jp.JPRSBHost;
import org.openbase.jul.extension.rsb.com.jp.JPRSBPort;
import org.openbase.type.domotic.authentication.LoginCredentialsType.LoginCredentials;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Base64;
import java.util.Dictionary;

/**
 * The {@link BCOHandlerFactory} is responsible for creating things and thing
 * handlers.
 *
 * @author Tamino Huxohl - Initial contribution
 */
@NonNullByDefault
@Component(configurationPid = "binding.bco", service = ThingHandlerFactory.class)
public class BCOHandlerFactory extends BaseThingHandlerFactory {

    private final Logger logger = LoggerFactory.getLogger(BCOHandlerFactory.class);

    @Override
    public boolean supportsThingType(final ThingTypeUID thingTypeUID) {
        return BCOBindingConstants.THING_TYPES.contains(thingTypeUID);
    }

    @Override
    protected @Nullable ThingHandler createHandler(final Thing thing) {
        return new UnitHandler(thing);
    }

    private static boolean initialActivate = true;

    @Override
    protected void activate(ComponentContext componentContext) {
        super.activate(componentContext);

        final Dictionary<String, Object> properties = componentContext.getProperties();

        try {
            final Integer oldPort = JPService.getProperty(JPRSBPort.class).getValue();
            final String oldHost = JPService.getProperty(JPRSBHost.class).getValue();
            logger.info("OldHost {}, oldPort {}, initAct {}", oldHost, oldPort, initialActivate);

            final Object rsbHost = properties.get("rsbHost");
            if (rsbHost instanceof String) {
                JPService.registerProperty(JPRSBHost.class, (String) rsbHost);
                JPService.getProperty(JPRSBHost.class).update((String) rsbHost);

            }

            final Object rsbPort = properties.get("rsbPort");
            if (rsbPort instanceof String) {
                JPService.registerProperty(JPRSBPort.class, Integer.parseInt((String) rsbPort));
                JPService.getProperty(JPRSBPort.class).update(Integer.parseInt((String) rsbPort));
            }
            final String[] args = {};
            JPService.parse(args);

            final Integer newPort = JPService.getProperty(JPRSBPort.class).getValue();
            final String newHost = JPService.getProperty(JPRSBHost.class).getValue();

            logger.info("Activate with RSBHost {} and RSBPort {}", newHost, newPort);
            if (!oldPort.equals(newPort) || !oldHost.equals(newHost)) {
                logger.info("RSBHost changed from {} to {}", oldHost, newHost);
                logger.info("RSBPort changed from {} to {}", oldPort, newPort);

                RSBSharedConnectionConfig.reload();
                logger.info("Middleware configuration finished.");

                // do not perform re-init on initial start because there is no need for it.
                if (!initialActivate) {
                    try {
                        logger.info("Reinit registries");
                        Registries.reinitialize();
                        logger.info("Reinit units");
                        Units.reinitialize();
                    } catch (CouldNotPerformException ex) {
                        logger.error("Could not reinitialize remotes after host and/or port change!", ex);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                } else {
                    initialActivate = false;
                }
            }

            if (!SessionManager.getInstance().isLoggedIn()) {
                try {
                    logger.info("Establish an authorized connection to bco...");
                    Object credentials = properties.get("credentials");
                    if (!(credentials instanceof String)) {
                        throw new NotAvailableException("Credentials");
                    }
                    Registries.waitForData();
                    Registries.waitForData();
                    LoginCredentials loginCredentials = LoginCredentials.newBuilder()
                            .setId(Registries.getUnitRegistry().getUnitConfigByAlias(UnitRegistry.OPENHAB_USER_ALIAS).getId())
                            .setSymmetric(false)
                            .setAdmin(false)
                            .setCredentials(ByteString.copyFrom(Base64.getDecoder().decode((String) credentials)))
                            .build();
                    SessionManager.getInstance().loginClient(loginCredentials.getId(), loginCredentials, true);
                    logger.info("Authorization successful");
                } catch (Exception e) {
                    logger.error("Could not login as openhab user", e);
                    if (!BCOLogin.getSession().isLoggedIn()) {
                        try {
                            BCOLogin.getSession().loginUserViaUsername("admin", "admin", true);
                        } catch (CouldNotPerformException ex) {
                            logger.error("Could not login admin", ex);
                        }
                    }
                }
            }
        } catch (JPServiceException ex) {
            logger.error("Could not read or update JPProperty", ex);
        }
    }

    @Override
    protected void deactivate(ComponentContext componentContext) {
        super.deactivate(componentContext);

        // shutdown binding
        // TODO: 18.12.20 implement proper binding shutdown
//        Registries.shutdown();
//        Units.shutdown();
    }
}
