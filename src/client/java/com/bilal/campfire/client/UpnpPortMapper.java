package com.bilal.campfire.client;

import org.bitlet.weupnp.GatewayDevice;
import org.bitlet.weupnp.GatewayDiscover;

import java.util.Map;

// Tries to make the current host directly reachable from the internet
// without them ever touching router settings - most home routers speak UPnP
// IGD and will grant a port mapping to whichever device on the LAN asks for
// one. When this succeeds, friends can connect straight to the host's public
// IP instead of every byte of gameplay being tunneled through the
// coordinator relay. This only ever runs on the host's machine, and only
// ever maps the fixed LAN port CampfireClient already opens
// (HOST_LAN_PORT/RelayHostMultiplexer's target), never anything else.
//
// UPnP discovery + mapping is blocking network I/O (SSDP multicast + SOAP
// calls, each with its own timeout) - callers must run this off the render
// thread.
final class UpnpPortMapper {

    private UpnpPortMapper() {
    }

    record Result(String externalIp, int externalPort) {
    }

    // Returns null if there's no UPnP-capable router, the router refused the
    // mapping (corporate network, UPnP disabled), or anything else went
    // wrong - all of which are completely normal outcomes, not errors worth
    // surfacing to the player. Callers fall back to the next connectivity
    // option (STUN, or eventually a "network doesn't support a direct
    // connection" message) rather than failing outright.
    static Result tryMapPort(int port) {
        try {
            GatewayDiscover discover = new GatewayDiscover();
            Map<?, GatewayDevice> gateways = discover.discover();
            if (gateways.isEmpty()) {
                System.out.println("[Campfire] UPnP: no gateway responded - router likely doesn't support UPnP.");
                return null;
            }

            GatewayDevice gateway = discover.getValidGateway();
            if (gateway == null) {
                System.out.println("[Campfire] UPnP: found a gateway, but none reported an active internet connection.");
                return null;
            }

            String externalIp = gateway.getExternalIPAddress();
            if (externalIp == null || externalIp.isBlank() || externalIp.equals("0.0.0.0")) {
                System.out.println("[Campfire] UPnP: gateway didn't report a usable external IP.");
                return null;
            }

            boolean mapped = gateway.addPortMapping(port, port, gateway.getLocalAddress().getHostAddress(),
                    "TCP", "Campfire (Minecraft world sharing)");
            if (!mapped) {
                System.out.println("[Campfire] UPnP: gateway rejected the port mapping request.");
                return null;
            }

            System.out.println("[Campfire] UPnP: mapped " + externalIp + ":" + port + " -> local port " + port);
            return new Result(externalIp, port);
        } catch (Exception e) {
            System.out.println("[Campfire] UPnP mapping failed (this is normal on many networks): " + e.getMessage());
            return null;
        }
    }
}
