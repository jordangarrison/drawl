self:
{
  config,
  lib,
  pkgs,
  ...
}:

let
  cfg = config.programs.drawl;
in
{
  options.programs.drawl = {
    enable = lib.mkEnableOption "drawl CLI — compile .drawl source to dot / mermaid C4 / excalidraw";

    package = lib.mkOption {
      type = lib.types.package;
      default = self.packages.${pkgs.system}.cli;
      defaultText = lib.literalExpression "self.packages.\${pkgs.system}.cli";
      description = "The drawl CLI package to install.";
    };
  };

  config = lib.mkIf cfg.enable {
    environment.systemPackages = [ cfg.package ];
  };
}
