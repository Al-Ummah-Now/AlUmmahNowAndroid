# Instrumented tests that run against the deployed QA (auth.qa / videos.api.qa), as module=class pairs.
# Sourced by the emulator scripts: PR/main CI leaves them out (notClass), the release gate runs only them.
# A new QA-backed test goes here.
QA_DEPLOYED=(
  "studio-mobile=net.alummahnow.studio.StudioQaFlowTest"
  "viewer-mobile=net.alummahnow.viewer.ViewerFlowTest"
)
export QA_DEPLOYED_TESTS=$(printf '%s\n' "${QA_DEPLOYED[@]}" | cut -d= -f2 | paste -sd, -)
