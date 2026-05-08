self:
{
  config,
  lib,
  pkgs,
  ...
}:

let
  cfg = config.services.drawl;
in
{
  options.services.drawl = {
    enable = lib.mkEnableOption "drawl SPA static-site host";

    package = lib.mkOption {
      type = lib.types.package;
      default = self.packages.${pkgs.system}.default;
      defaultText = lib.literalExpression "self.packages.\${pkgs.system}.default";
      description = "The drawl package to use.";
    };

    host = lib.mkOption {
      type = lib.types.str;
      default = "localhost";
      example = "drawl.example.com";
      description = "Public hostname for the site.";
    };

    openFirewall = lib.mkOption {
      type = lib.types.bool;
      default = false;
      description = "Whether to open ports 80 and 443 in the firewall.";
    };

    nginx = {
      enable = lib.mkOption {
        type = lib.types.bool;
        default = false;
        description = "Whether to configure an nginx virtualhost for drawl.";
      };

      enableACME = lib.mkOption {
        type = lib.types.bool;
        default = true;
        description = "Whether to enable ACME (Let's Encrypt) for the nginx virtualhost.";
      };
    };
  };

  config = lib.mkIf cfg.enable {
    assertions = [
      {
        assertion = !(cfg.nginx.enable && cfg.host == "localhost");
        message = "services.drawl: nginx is enabled but host is 'localhost'. ACME certificate provisioning will fail. Set a real public hostname.";
      }
      {
        assertion = !(cfg.nginx.enable && cfg.nginx.enableACME && (builtins.match "^[0-9.:]+$" cfg.host != null || lib.hasSuffix ".local" cfg.host));
        message = "services.drawl: ACME is enabled but host appears to be an IP address or .local domain. ACME requires a public DNS name.";
      }
      {
        assertion = !(cfg.openFirewall && cfg.nginx.enable);
        message = "services.drawl: openFirewall and nginx are both enabled. openFirewall opens ports 80/443 directly. Use nginx's own firewall settings instead.";
      }
    ];

    networking.firewall.allowedTCPPorts = lib.mkIf cfg.openFirewall [ 80 443 ];

    services.nginx = lib.mkIf cfg.nginx.enable {
      enable = true;
      recommendedProxySettings = true;
      recommendedTlsSettings = true;
      recommendedOptimisation = true;
      recommendedGzipSettings = true;

      virtualHosts.${cfg.host} = {
        forceSSL = true;
        enableACME = cfg.nginx.enableACME;
        root = "${cfg.package}/share/drawl";

        locations."/" = {
          tryFiles = "$uri $uri/ /index.html =404";
        };
      };
    };
  };
}
