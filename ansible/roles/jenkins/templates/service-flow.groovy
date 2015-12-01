import groovy.json.JsonSlurper

def registry = "10.100.198.200:5000/"
def swarmMaster = "10.100.192.200"
def proxy = "10.100.192.200"
def currentColor = getCurrentColor(swarmMaster, service)
def nextColor = getNextColor(currentColor)

node("cd") {
    env.PYTHONUNBUFFERED = 1

    stage "> Provisioning"
    if (provision.toBoolean()) {
        sh "ansible-playbook /vagrant/ansible/swarm.yml \
            -i /vagrant/ansible/hosts/prod"
        sh "ansible-playbook /vagrant/ansible/nginx.yml \
            -i /vagrant/ansible/hosts/prod --extra-vars \
            \"proxy_host=swarm-master\""
    }

    stage "> Pre-Deployment"
    git url: "https://github.com/vfarcic/${service}.git"
    if (build.toBoolean()) {
        sh "sudo docker build -t ${registry}${service}-tests -f Dockerfile.test ."
        sh "sudo docker-compose -f docker-compose-dev.yml run --rm tests"
        def app = docker.build "${registry}${service}"
        app.push()
    }

    stage "> Deployment"
    env.DOCKER_HOST = "tcp://${swarmMaster}:2375"
    def instances = getInstances(swarmMaster, service)
    if (build.toBoolean()) {
        sh "docker-compose -f docker-compose-swarm.yml pull app-${nextColor}"
    }
    sh "docker-compose -f docker-compose-swarm.yml --x-networking up -d db"
    sh "docker-compose -f docker-compose-swarm.yml rm -f app-${nextColor}"
    sh "docker-compose -f docker-compose-swarm.yml --x-networking scale app-${nextColor}=$instances"
    sh "curl -X PUT -d $instances http://${swarmMaster}:8500/v1/kv/${service}/instances"

    stage "> Post-Deployment"
    def address = getAddress(swarmMaster, service, nextColor)
    env.DOCKER_HOST = ""
    try {
        sh "docker-compose -f docker-compose-dev.yml run --rm -e DOMAIN=http://$address integ"
    } catch (e) {
        sh "docker-compose -f docker-compose-swarm.yml stop app-${nextColor}"
        error("Pre-integration tests failed")
    }
    sh "consul-template -consul ${swarmMaster}:8500 -template 'nginx-upstreams-${nextColor}.ctmpl:nginx-upstreams.conf' -once"
    stash includes: 'nginx-*.conf', name: 'nginx'
}
node("lb") {
    unstash 'nginx'
    sh "sudo cp nginx-includes.conf /data/nginx/includes/${service}.conf"
    sh "sudo cp nginx-upstreams.conf /data/nginx/upstreams/${service}.conf"
    sh "docker kill -s HUP nginx"
}
node("cd") {
    try {
        sh "docker-compose -f docker-compose-dev.yml run --rm -e DOMAIN=http://${proxy} integ"
    } catch (e) {
        if (currentColor != "") {
            updateProxy(swarmMaster, service, currentColor)
        }
        sh "docker-compose -f docker-compose-swarm.yml stop app-${nextColor}"
        error("Post-integration tests failed")
    }
    sh "curl -X PUT -d ${nextColor} http://${swarmMaster}:8500/v1/kv/${service}/color"
    if (currentColor != "") {
        env.DOCKER_HOST = "tcp://${swarmMaster}:2375"
        sh "docker-compose -f docker-compose-swarm.yml stop app-${currentColor}"
    }
    // TODO: Add pings
    env.DOCKER_HOST = ""
    sh "docker push ${registry}${service}-tests"
}

def getCurrentColor(swarmMaster, service) {
    try {
        return "http://${swarmMaster}:8500/v1/kv/${service}/color?raw".toURL().text
    } catch(e) {
        return ""
    }
}

def getNextColor(currentColor) {
    if (currentColor == "blue") {
        return "green"
    } else {
        return "blue"
    }
}

def getInstances(swarmMaster, service) {
    if (instances.toInteger() == 0) {
        try {
            instances = "http://${swarmMaster}:8500/v1/kv/${service}/instances?raw".toURL().text
        } catch (e) {
            return 1
        }
    }
    return instances.toInteger()
}

def getAddress(swarmMaster, service, color) {
    echo "http://${swarmMaster}:8500/v1/catalog/service/${service}-${color}"
    def serviceJson = "http://${swarmMaster}:8500/v1/catalog/service/${service}-${color}".toURL().text
    def result = new JsonSlurper().parseText(serviceJson)[0]
    return result.ServiceAddress + ":" + result.ServicePort
}
