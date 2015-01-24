from fabric.api import run, sudo, env, task, local, put, parallel
from fabric.context_managers import cd
import logging
from fabtools import require

env.user = 'root'
env.use_ssh_config = True

PACKAGES = []

@task
def install_packages():
    require.deb.package(PACKAGES)


@task
def command_nginx(command='reload'):
    run("service nginx {}".format(command))

@task
def cassax(command='status'):
    run("service cassandra {}".format(command))

@task
def runx(command):
    run("{}".format(command))


@task
def compile():
  local("lein uberjar")

@task
def release(compile_app=True):
  deploy()

@task
def deploy_assets():
  put("resources/public/css/style.css", "/var/www/hackersome/public/css/style.css")
  put("resources/public/js/app.js", "/var/www/hackersome/public/js/app.js")
  put("logback.xml", "/var/www/hackersome/logback.xml")

@task
def deploy():
  version = open("VERSION").readlines()[0]
  jar_file = "target/hsm.jar".format(version)
  put(jar_file, "/var/www/hackersome/hackersome-latest.jar")

  deploy_assets()
  
  with cd("/var/www/hackersome"):
    try:
      sudo("mv hackersome.jar hackersome-old.jar")
    except:
      pass

    sudo("mv hackersome-latest.jar hackersome.jar")

  sudo("supervisorctl restart prod_hackersome")


@task
def hostname():
    run("hostname")


@task
def disk_status():
    run("df -h")

@task
def nlog():
    with cd("/var/log/nginx/hackersome.com"):
        run("tail -f access.log")