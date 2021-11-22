# dos

- Dense or Sparse : Optimal SPMM-as-a-Service for Big-Data Processing

<br><br>

## How to use data-generation and dos

### 1. Install Docker on Amazon EC2(Ubuntu, t2.medium, 20GB)

```
sudo apt-get update -y
sudo apt-get remove docker docker-engine docker.io
sudo apt-get install docker.io -y
sudo service docker start
sudo chmod 666 /var/run/docker.sock
sudo usermod -a -G docker ubuntu
```

### 2. Run Docker

```
sudo docker pull tensorflow/tensorflow:2.5.0
sudo docker run -it tensorflow/tensorflow:2.5.0 bash
```

### 3. Setting

```
apt-get update -y
apt-get install git -y
cd home
git clone https://github.com/kmu-bigdata/dos.git
cd dos/dos/
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

## How to build spark-3.1.2

### 1. Setting on Amazon emr-6.4.0

```
sudo yum update -y
sudo yum install git -y
cd /home/hadoop
git clone https://github.com/kmu-bigdata/dos.git
```

### 2. Build

```
cd dos/spark-3.1.2 && ./build/mvn -pl :spark-mllib_2.12 -DskipTests clean install
```

### 3. Change the existing mllib package of Amazon EMR

```
sudo mv /home/hadoop/dos/spark-3.1.2/mllib/target/spark-mllib_2.12-3.1.2.jar /usr/lib/spark/jars/spark-mllib_2.12-3.1.2-amzn-0.jar
```

### 4. Run Spark

```
spark-shell
```

### 5. Simple Matrix Multiplication Code

```
import org.apache.spark.mllib.linalg.SparseMatrix
import java.util.Random;

val l_sm = SparseMatrix.sprand(10,20,0.001,new Random(24))
val r_sm = SparseMatrix.sprand(20,10,0.005,new Random(24))

l_sm.multiply(r_sm)
```

<br><br>

## How to create Microservice using AWS

### 1. Setting on Amazon EC2(Ubuntu, t2.medium, 20GB)

```
sudo apt-get update -y
sudo apt-get install git -y
git clone https://github.com/kmu-bigdata/dos.git
```

### 2. Install Docker

```
sudo apt-get update -y
sudo apt-get remove docker docker-engine docker.io
sudo apt-get install docker.io -y
sudo service docker start
sudo chmod 666 /var/run/docker.sock
sudo usermod -a -G docker ubuntu
```

### 3. Build Container Image using Dockerfile

```
cd dos/microservice
docker build -t "image-name" .
```

### 4. Upload Container Image to Amazon ECR

```
aws configure
export ACCOUNT_ID=$(aws sts get-caller-identity --output text --query Account)
echo "export ACCOUNT_ID=${ACCOUNT_ID}" | tee -a ~/.bash_profile

docker tag "image-name" $ACCOUNT_ID.dkr.ecr."region-name".amazonaws.com/"ecr-name"
aws ecr get-login-password --region "region-name" | docker login --username AWS --password-stdin $ACCOUNT_ID.dkr.ecr."region-name".amazonaws.com
docker push $ACCOUNT_ID.dkr.ecr."region-name".amazonaws.com/"ecr-name"
```

### 5. Create a AWS Lambda based on Amazon ECR Container Image

### 6. Write a Lambda function that recommends an optimal multiplication method based on matrix multiplication information

- dos/microservice/lambda_function.py

### 7. Create Amazon API Gateway and connect AWS Lambda trigger
