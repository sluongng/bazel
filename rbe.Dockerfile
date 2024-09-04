FROM gcr.io/flame-public/rbe-ubuntu24-04:latest

RUN apt update && \
    apt install -y --no-install-recommends \
      ca-certificates \
      clang-20 \
      clang-tools-20 \
      curl \
      git \
      iproute2 \
      iputils-ping \
      locales \
      openjdk-21-jdk \
      openssl \
      patch \
      perl \
      python3 \
      unzip \
      zip && \
    ln -sf /usr/bin/clang-20 /usr/bin/clang && \
    sed -i 's/^# *\(en_US.UTF-8 UTF-8\)/\1/' /etc/locale.gen && \
    sed -i 's/^# *\(en_US ISO-8859-1\)/\1/' /etc/locale.gen && \
    locale-gen && \
    apt-get clean && rm -rf /var/lib/apt/lists/*
