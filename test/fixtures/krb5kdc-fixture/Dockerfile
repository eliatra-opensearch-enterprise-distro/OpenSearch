FROM ubuntu:14.04
RUN apt update -y
RUN apt upgrade -y
ADD . /fixture
#https://stackoverflow.com/questions/69168860/how-to-fix-read-only-error-when-adding-host-in-docker
#RUN echo kerberos.build.opensearch.org > /etc/hostname
#RUN echo "127.0.0.1 kerberos.build.opensearch.org" >> /etc/hosts
RUN bash /fixture/src/main/resources/provision/installkdc.sh

EXPOSE 88
EXPOSE 88/udp

CMD sleep infinity
