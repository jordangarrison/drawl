{ pkgs, lib }:

let
  src = lib.cleanSourceWith {
    src = ../.;
    filter = path: type:
      let baseName = baseNameOf (toString path);
      in
        (lib.cleanSourceFilter path type)
        && !(builtins.elem baseName [
          "node_modules"
          ".shadow-cljs"
          ".cpcache"
          ".bbin"
          ".clj-kondo"
          ".lsp"
          ".direnv"
          ".nrepl-port"
          "result"
        ]);
  };
in
pkgs.stdenv.mkDerivation {
  pname = "drawl";
  version = "0.0.1";

  inherit src;

  nativeBuildInputs = with pkgs; [
    jdk21
    clojure
    nodejs_22
    cacert
    git
  ];

  buildPhase = ''
    runHook preBuild

    export HOME=$(mktemp -d)
    export JAVA_HOME=${pkgs.jdk21}

    # Drop any dev-mode shadow-cljs output that snuck in via src.
    rm -rf public/js

    # Fetch deps (npm + clojure/maven/gitlibs) — network access
    # is permitted because this is a fixed-output derivation.
    npm ci --no-audit --no-fund
    patchShebangs node_modules

    # shadow-cljs release pulls Clojure deps into ~/.m2 + ~/.gitlibs
    # the first time it runs, then emits public/js/main.js.
    npx shadow-cljs release app

    runHook postBuild
  '';

  installPhase = ''
    runHook preInstall

    mkdir -p $out/share/drawl
    cp -r public/. $out/share/drawl/

    runHook postInstall
  '';

  outputHashMode = "recursive";
  outputHashAlgo = "sha256";
  outputHash = "sha256-OY1wcxYSKjP6D8570Fpk16gKuM8EOpak4bW2slp60LU=";

  meta = {
    description = "drawl SPA — a Lisp for C4 diagrams, browser-native";
    license = lib.licenses.mit;
  };
}
