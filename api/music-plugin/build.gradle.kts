plugins {
    id("base-conventions")
    id("game-cache-test-conventions")
}

kotlin {
    explicitApi()
}

dependencies {
    implementation(libs.guice)
    implementation(projects.api.config)
    implementation(projects.api.player)
    implementation(projects.api.script)
    implementation(projects.api.generated)
    implementation(projects.api.utils.utilsVars)
    implementation(projects.engine.events)
    implementation(projects.engine.game)
    implementation(projects.engine.plugin)
    testImplementation(libs.kotlin.coroutines.core)
    testImplementation(libs.rsprot.api)
    testImplementation(projects.api.music)
    testImplementation(projects.api.random)
    testImplementation(projects.engine.annotations)
    testImplementation(projects.engine.coroutine)
}
