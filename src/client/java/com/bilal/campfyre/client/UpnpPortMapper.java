package com.bilal.campfyre.client;

import org.bitlet.weupnp.GatewayDevice;
import org.bitlet.weupnp.GatewayDiscover;

import java.util.Map;

// Tries to make the current host directly reachable from the internet
// without them ever touching router settings - most home routers speak UPnP
// IGD and will grant a port mapping to whichever device on the LAN asks for
// one. When this succeeds, friends can connect straight to the host's public
// IP instead of every byte of gameplay being tunneled through the
// coordinator relay. This only ever runs on the host's machine, and only
// ever maps the fixed LAN port CampfyreClient already opens
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

    // Tracks whatever mapping tryMapPort() last successfully created, so
    // unmapPort() can remove it later. Only ever one mapping active at a
    // time (this mod only ever maps the single fixed HOST_LAN_PORT), so a
    // static field is enough - no per-call handle needs to be threaded
    // through CampfyreClient's hosting-mode bookkeeping.
    private static volatile GatewayDevice mappedGateway;
    private static volatile int mappedPort = -1;

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
                System.out.println("[Campfyre] UPnP: no gateway responded - router likely doesn't support UPnP.");
                return null;
            }

            GatewayDevice gateway = discover.getValidGateway();
            if (gateway == null) {
                System.out.println("[Campfyre] UPnP: found a gateway, but none reported an active internet connection.");
                return null;
            }

            String externalIp = gateway.getExternalIPAddress();
            if (externalIp == null || externalIp.isBlank() || externalIp.equals("0.0.0.0")) {
                System.out.println("[Campfyre] UPnP: gateway didn't report a usable external IP.");
                return null;
            }

            boolean mapped = gateway.addPortMapping(port, port, gateway.getLocalAddress().getHostAddress(),
                    "TCP", "Campfyre (Minecraft world sharing)");
            if (!mapped) {
                System.out.println("[Campfyre] UPnP: gateway rejected the port mapping request.");
                return null;
            }

            System.out.println("[Campfyre] UPnP: mapped " + externalIp + ":" + port + " -> local port " + port);
            mappedGateway = gateway;
            mappedPort = port;
            return new Result(externalIp, port);
        } catch (Exception e) {
            System.out.println("[Campfyre] UPnP mapping failed (this is normal on many networks): " + e.getMessage());
            return null;
        }
    }

    // Removes whatever mapping tryMapPort() last created, if any - called
    // when this client stops being host (quit, migrated away, left the
    // group). Without this, every hosting session left the router forwarding
    // the internet straight to the host's LAN Minecraft port permanently:
    // normal quit, crash, or leaving the group all left it standing forever,
    // clearable only by a router reboot or manually digging through the
    // router's admin page. Blocking network I/O like tryMapPort - callers
    // must run this off the render/server thread.
    static void unmapPort() {
        GatewayDevice gateway = mappedGateway;
        int port = mappedPort;
        if (gateway == null || port < 0) return;
        mappedGateway = null;
        mappedPort = -1;
        try {
            boolean removed = gateway.deletePortMapping(port, "TCP");
            System.out.println("[Campfyre] UPnP: removed mapping for port " + port + " -> " + removed);
        } catch (Exception e) {
            System.out.println("[Campfyre] UPnP: failed to remove port mapping (router may still show it): " + e.getMessage());
        }
    }
}
