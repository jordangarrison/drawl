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
          "public"
          "test"
        ]);
  };
in
pkgs.stdenv.mkDerivation {
  pname = "drawl-cli";
  version = "0.0.1";

  inherit src;

  nativeBuildInputs = [ pkgs.makeWrapper ];

  # Runtime tools the CLI shells to (today: bb for the dispatcher; future
  # `drawl render` subcommand will use dot/mmdc — see issue #10).
  buildInputs = [
    pkgs.babashka
    pkgs.graphviz
    pkgs.mermaid-cli
  ];

  dontConfigure = true;
  dontBuild = true;

  installPhase = ''
    runHook preInstall

    mkdir -p $out/share/drawl-cli $out/bin
    cp -r src bb.edn $out/share/drawl-cli/

    makeWrapper ${pkgs.babashka}/bin/bb $out/bin/drawl \
      --add-flags "--config $out/share/drawl-cli/bb.edn drawl" \
      --prefix PATH : ${lib.makeBinPath [ pkgs.graphviz pkgs.mermaid-cli ]}

    runHook postInstall
  '';

  meta = {
    description = "drawl CLI — compile .drawl source to dot / mermaid C4 / excalidraw";
    license = lib.licenses.mit;
    mainProgram = "drawl";
    platforms = lib.platforms.unix;
  };
}
