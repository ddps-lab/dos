# dos

Dense or Sparse : Optimal SPMM-as-a-Service for Big-Data Processing

<br><br>

## How to use data-generation and dos

### Install Docker

```
sudo apt-get update -y
sudo apt-get remove docker docker-engine docker.io
sudo apt-get install docker.io -y
sudo service docker start
sudo chmod 666 /var/run/docker.sock
sudo usermod -a -G docker ubuntu
```

### Run Docker

```
sudo docker pull tensorflow/tensorflow:2.2.0
sudo docker run -it tensorflow/tensorflow:2.2.0 bash
```

### Setting

```
apt-get update -y
apt-get install git -y
cd home
git clone https://github.com/kmu-bigdata/dos.git
cd dos
pip install -r requirements.txt
```

### Data Generation

```
cd data-generation
./data-generation.sh
```

### DoS Train

```
cd dos
python train.py
```

### DoS Test

```
cd dos
python test.py
```

### DoS Inference

```
cd dos
python inference.py
```

<br><br>

## How to build spark-3.1.2 on Amazon emr-6.4.0

### Setting

```
sudo yum update -y
sudo yum install git -y
cd /home/hadoop
git clone https://github.com/kmu-bigdata/dos.git
```

### Build

```
cd dos/spark-3.1.2 && ./build/mvn -pl :spark-mllib_2.12 -DskipTests clean install
```

### Change the existing mllib package of Amazon EMR

```
sudo mv /home/hadoop/dos/spark-3.1.2/mllib/target/spark-mllib_2.12-3.1.2.jar /usr/lib/spark/jars/spark-mllib_2.12-3.1.2-amzn-0.jar
```

### Run Spark

```
spark-shell
```

<br><br>

## How to use Microservice

```

```
