{
  description = "drawl — a Lisp for diagrams. C4-aligned, browser-native, CLI-friendly.";

  inputs = {
    nixpkgs.url = "github:nixos/nixpkgs?ref=nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = nixpkgs.legacyPackages.${system};
      in {
        packages.default = pkgs.callPackage ./nix/package.nix {
          inherit (nixpkgs) lib;
        };

        packages.cli = pkgs.callPackage ./nix/cli.nix {
          inherit (nixpkgs) lib;
        };

        apps.cli = {
          type = "app";
          program = "${self.packages.${system}.cli}/bin/drawl";
        };

        devShells.default = import ./nix/devshell.nix { inherit pkgs; };
      }) // {
      nixosModules.default = import ./nix/module.nix self;
      nixosModules.cli = import ./nix/cli-module.nix self;
    };
}
