# dos

- Dense or Sparse : Optimal SPMM-as-a-Service for Big-Data Processing

<br><br>

## How to use data-generation and dos on Tensorflow Container

### 1. Install Docker

```bash
sudo apt-get update -y
sudo apt-get remove docker docker-engine docker.io
sudo apt-get install docker.io -y
sudo service docker start
sudo chmod 666 /var/run/docker.sock
sudo usermod -a -G docker ubuntu
```

### 2. Run Docker

```bash
sudo docker pull tensorflow/tensorflow:2.2.0
sudo docker run -it tensorflow/tensorflow:2.2.0 bash
```

### 3. Setting

```bash
apt-get update -y
apt-get install git -y
cd home
git clone https://github.com/kmu-bigdata/dos.git
cd dos
pip install -r requirements.txt
```

### 4. Data Generation

```bash
cd data-generation
./data-generation.sh
```

### 5. DoS Train

```bash
cd dos
python train.py
```

### 6. DoS Test

```bash
cd dos
python test.py
```

### 7. DoS Inference

```bash
cd dos
python inference.py
```

<br><br>

## How to build spark-3.1.2 on Amazon emr-6.4.0

### 1. Setting

```bash
sudo yum update -y
sudo yum install git -y
cd /home/hadoop
git clone https://github.com/kmu-bigdata/dos.git
```

### 2. Build

```bash
cd dos/spark-3.1.2 && ./build/mvn -pl :spark-mllib_2.12 -DskipTests clean install
```

### 3. Change the existing mllib package of Amazon EMR

```bash
sudo mv /home/hadoop/dos/spark-3.1.2/mllib/target/spark-mllib_2.12-3.1.2.jar /usr/lib/spark/jars/spark-mllib_2.12-3.1.2-amzn-0.jar
```

### 4. Run Spark

```bash
spark-shell
```

<br><br>

## How to use Microservice

### 1. Setting

```bash
sudo apt-get update -y
sudo apt-get install git -y
git clone https://github.com/kmu-bigdata/dos.git
```

### 2. Install Docker

```bash
sudo apt-get update -y
sudo apt-get remove docker docker-engine docker.io
sudo apt-get install docker.io -y
sudo service docker start
sudo chmod 666 /var/run/docker.sock
sudo usermod -a -G docker ubuntu
```

### 3. Build Container Image using Dockerfile

```bash
cd dos/microservice
docker build -t microservice-image .
```

### 4. Upload Container Image to Amazon ECR

### 5. Create a Lambda Function based on Amazon ECR Container Image

### 6. Write a Lambda function that recommends an optimal multiplication method based on matrix multiplication information

- dos/microservice/lambda_function.py

### 7. Create API GATEWAY and connect Lambda Function trigger
